package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Обмен ключа Сбера на токен: что уходит в OAuth-дверь и как долго
 * токен держится.
 *
 * <p>Сервер поднимается тут же, на случайном порту: наружу тест не ходит —
 * ни за деньги, ни за чужой доступностью. Часы подставные: срок жизни
 * токена — тридцать минут, и ждать их в тесте никто не будет.
 */
class GigaChatAuthTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastRqUid = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final List<String> tokens = new ArrayList<>();

    /** Подставные часы: сдвигаются рукой. */
    private Instant now = Instant.parse("2026-10-08T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Дверь, выдающая токены по очереди из списка; срок — через полчаса от «сейчас». */
    private void issuing(int status, String... issued) {
        tokens.addAll(List.of(issued));
        server.createContext("/api/v2/oauth", exchange -> {
            var headers = exchange.getRequestHeaders();
            lastAuth.set(headers.getFirst("Authorization"));
            lastRqUid.set(headers.getFirst("RqUID"));
            lastContentType.set(headers.getFirst("Content-Type"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            var at = Math.min(exchanges.getAndIncrement(), tokens.size() - 1);
            var body = status == 200
                    ? "{\"access_token\":\"" + tokens.get(at) + "\",\"expires_at\":"
                            + now.plus(Duration.ofMinutes(30)).toEpochMilli() + "}"
                    : "{\"code\":" + status + ",\"message\":\"Unauthorized\"}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private GigaChatAuth auth() {
        var here = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/oauth");
        return new GigaChatAuth(HttpClient.newHttpClient(), here, json, "dGVzdDpzZWNyZXQ=",
                "GIGACHAT_API_PERS", Duration.ofSeconds(5), clock);
    }

    // Форма запроса — та, которую требует дверь Сбера: Basic с ключом, RqUID,
    // область доступа в теле формы.
    @Test
    void theExchangeCarriesBasicKeyRqUidAndScope() {
        issuing(200, "token-1");

        assertThat(auth().token()).isEqualTo("token-1");

        assertThat(lastAuth.get()).isEqualTo("Basic dGVzdDpzZWNyZXQ=");
        assertThat(lastRqUid.get()).as("RqUID обязателен и должен быть UUID")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(lastContentType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(lastBody.get()).isEqualTo("scope=GIGACHAT_API_PERS");
    }

    // Токен живёт полчаса: пока он жив, дверь не беспокоят.
    @Test
    void aLiveTokenIsReusedWithoutAnotherExchange() {
        issuing(200, "token-1", "token-2");
        var auth = auth();

        auth.token();
        now = now.plus(Duration.ofMinutes(10));
        var again = auth.token();

        assertThat(again).isEqualTo("token-1");
        assertThat(exchanges.get()).isEqualTo(1);
    }

    // Обновиться надо ДО истечения, а не после первого 401: за минуту
    // до срока токен считается протухшим.
    @Test
    void theTokenIsRefreshedShortlyBeforeItExpires() {
        issuing(200, "token-1", "token-2");
        var auth = auth();

        auth.token();
        now = now.plus(Duration.ofMinutes(29)).plusSeconds(30);

        assertThat(auth.token()).isEqualTo("token-2");
        assertThat(exchanges.get()).isEqualTo(2);
    }

    // Отозванный ключ: токен забывается, следующий вызов меняет ключ снова.
    @Test
    void invalidationForcesANewExchange() {
        issuing(200, "token-1", "token-2");
        var auth = auth();

        auth.token();
        auth.invalidate();

        assertThat(auth.token()).isEqualTo("token-2");
        assertThat(exchanges.get()).isEqualTo(2);
    }

    // Отказ двери — исключение с кодом и началом тела, а не пустой токен.
    @Test
    void aRefusalIsAnErrorAndCarriesTheReason() {
        issuing(401);

        assertThatThrownBy(() -> auth().token())
                .hasMessageContaining("401")
                .hasMessageContaining("Unauthorized");
    }

    // Готовый ключ из кабинета отдаётся как есть; пара — собирается в base64.
    @Test
    void credentialsComeFromTheKeyOrArePackedFromThePair() {
        assertThat(GigaChatAuth.credentials("  abc==  ", "", "")).isEqualTo("abc==");
        assertThat(GigaChatAuth.credentials("abc==", "id", "secret"))
                .as("готовый ключ в приоритете").isEqualTo("abc==");
        assertThat(GigaChatAuth.credentials("", "test", "secret")).isEqualTo("dGVzdDpzZWNyZXQ=");
        assertThatThrownBy(() -> GigaChatAuth.credentials("", "test", ""))
                .hasMessageContaining("GIGACHAT_AUTH_KEY")
                .hasMessageContaining("GIGACHAT_CLIENT_SECRET");
    }
}
