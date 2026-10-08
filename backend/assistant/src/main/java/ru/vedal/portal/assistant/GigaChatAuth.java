package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Токен доступа GigaChat: получить, подержать, обновить.
 *
 * <p><b>Как устроен вход у Сбера.</b> В отличие от Яндекса, статического
 * ключа к API нет. Ключ авторизации (Authorization key из личного кабинета,
 * он же {@code base64(client_id:client_secret)}) меняется на access token
 * через OAuth-дверь {@code ngw.devices.sberbank.ru:9443/api/v2/oauth},
 * и живёт токен тридцать минут. Менять ключ на токен перед каждым вопросом
 * посетителя — лишний обход и лишняя секунда; поэтому токен хранится здесь
 * и обновляется заранее, за минуту до истечения, а не по первому отказу.
 *
 * <p><b>Один на всех.</b> Генерация и эмбеддинги ходят с одним и тем же
 * токеном, и держать по кэшу на каждого значило бы удвоить обмены
 * с OAuth-дверью. Класс потокобезопасен: вопросы посетителей идут
 * параллельно, а обмен ключа на токен должен случиться один раз.
 *
 * <p><b>Что не хранится.</b> Ни ключ, ни токен не пишутся в журнал
 * и не попадают в текст ошибок: репозиторий публичный, журналы читают
 * люди, а ключ оплачивает счёт.
 */
public final class GigaChatAuth {

    private static final Logger log = LoggerFactory.getLogger(GigaChatAuth.class);

    /** OAuth-дверь Сбера. Адрес приходит конструктором — ради теста. */
    public static final String CLOUD_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";

    /** Запас до истечения: обновиться раньше, чем дверь ответит 401. */
    static final Duration MARGIN = Duration.ofSeconds(60);

    private final HttpClient http;
    private final URI url;
    private final ObjectMapper json;
    private final String basic;
    private final String scope;
    private final Duration timeout;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public GigaChatAuth(HttpClient http, URI url, ObjectMapper json, String basic, String scope,
                        Duration timeout) {
        this(http, url, json, basic, scope, timeout, Clock.systemUTC());
    }

    GigaChatAuth(HttpClient http, URI url, ObjectMapper json, String basic, String scope,
                 Duration timeout, Clock clock) {
        this.http = http;
        this.url = url;
        this.json = json;
        this.basic = basic;
        this.scope = scope;
        this.timeout = timeout;
        this.clock = clock;
    }

    /**
     * Ключ для заголовка {@code Authorization: Basic}.
     *
     * <p>Кабинет Сбера выдаёт готовый Authorization key — это уже
     * {@code base64(client_id:client_secret)}, и его отдаём как есть.
     * Если вместо него заданы пара идентификатор + секрет, собираем сами.
     * Готовый ключ в приоритете: он — то, что человек скопировал из
     * кабинета, и спорить с ним, пересобирая из пары, значило бы завести
     * второй способ ошибиться.
     */
    public static String credentials(String authKey, String clientId, String clientSecret) {
        if (authKey != null && !authKey.isBlank()) return authKey.strip();
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("""
                    Доступ к GigaChat не задан. Нужен GIGACHAT_AUTH_KEY — Authorization key \
                    из кабинета developers.sber.ru (проект → GigaChat API → \
                    «Ключ авторизации»), либо пара GIGACHAT_CLIENT_ID и \
                    GIGACHAT_CLIENT_SECRET того же проекта.""");
        }
        return Base64.getEncoder().encodeToString(
                (clientId.strip() + ":" + clientSecret.strip()).getBytes(StandardCharsets.UTF_8));
    }

    /** HTTP-клиент, которым пользуются и этот класс, и те, кто ходит с его токеном. */
    public HttpClient http() {
        return http;
    }

    /** Действующий токен: из кэша, если он ещё жив, иначе — свежий. */
    public synchronized String token() {
        if (token != null && clock.instant().plus(MARGIN).isBefore(expiresAt)) {
            return token;
        }
        refresh();
        return token;
    }

    /**
     * Забыть токен. Зовётся, когда дверь ответила 401 раньше срока —
     * например, ключ отозвали и выдали заново; следующий вызов
     * {@link #token()} обменяет ключ ещё раз.
     */
    public synchronized void invalidate() {
        token = null;
        expiresAt = Instant.EPOCH;
    }

    private void refresh() {
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                // Уникальный идентификатор запроса — дверь требует его обязательно
                // и по нему же ищет запрос в своих журналах при разборе.
                .header("RqUID", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание токена GigaChat прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("OAuth-дверь GigaChat недоступна: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("OAuth-дверь GigaChat ответила " + response.statusCode()
                    + ": " + head(response.body()));
        }

        var root = json.readTree(response.body());
        var accessToken = root.path("access_token").asString(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("OAuth-дверь GigaChat ответила без access_token: "
                    + head(response.body()));
        }

        // expires_at — момент истечения в миллисекундах Unix. Нет его —
        // считаем по документации: тридцать минут с этой секунды.
        var expires = root.path("expires_at");
        expiresAt = expires.isNumber()
                ? Instant.ofEpochMilli(expires.asLong())
                : clock.instant().plus(Duration.ofMinutes(30));
        token = accessToken;
        log.info("Токен GigaChat получен, действует до {}", expiresAt);
    }

    private static String head(String body) {
        return body == null ? "" : body.length() <= 400 ? body : body.substring(0, 400);
    }
}
