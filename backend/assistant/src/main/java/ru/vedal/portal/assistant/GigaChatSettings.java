package ru.vedal.portal.assistant;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Настройки GigaChat, собранные в одном месте.
 *
 * <p>Полей у провайдера Сбера больше, чем у Яндекса: ключ (или пара),
 * область доступа, две модели, два адреса и путь к сертификату. Тащить
 * их десятком {@code @Value} через каждый бин значило бы повторять список
 * в трёх местах; здесь он один, а проверка — в {@link #auth}, и только
 * тогда, когда провайдер выбран: при {@code VEDAL_LLM_PROVIDER=yandex}
 * отсутствие ключа Сбера — не ошибка.
 *
 * @param authKey      Authorization key из кабинета ({@code GIGACHAT_AUTH_KEY})
 * @param clientId     альтернатива ключу: идентификатор ({@code GIGACHAT_CLIENT_ID})
 * @param clientSecret и секрет ({@code GIGACHAT_CLIENT_SECRET}) проекта
 * @param scope        область доступа: {@code GIGACHAT_API_PERS} (физлицо),
 *                     {@code GIGACHAT_API_B2B} (ИП и юрлицо по предоплате),
 *                     {@code GIGACHAT_API_CORP} (юрлицо по постоплате)
 * @param model        модель генерации: {@code GigaChat}, {@code GigaChat-Pro},
 *                     {@code GigaChat-Max}
 * @param embeddingsModel модель эмбеддингов: {@code Embeddings} или
 *                     {@code EmbeddingsGigaR}
 * @param embeddingsDimension ожидаемая длина вектора модели эмбеддингов
 * @param authUrl      OAuth-дверь (настройкой — ради теста и подмены при разборе)
 * @param apiUrl       корень API, без завершающей косой черты
 * @param caBundle     путь к PEM с «Russian Trusted Root CA»; пусто — только
 *                     штатное хранилище JVM
 */
public record GigaChatSettings(
        String authKey,
        String clientId,
        String clientSecret,
        String scope,
        String model,
        String embeddingsModel,
        int embeddingsDimension,
        String authUrl,
        String apiUrl,
        String caBundle) {

    public static final String PROVIDER = "gigachat";

    /**
     * Обменник ключа на токен — он же держатель HTTP-клиента с доверием
     * к сертификату Сбера. Здесь и проверяется, что настроено всё нужное.
     */
    public GigaChatAuth auth(ObjectMapper json, Duration timeout) {
        var basic = GigaChatAuth.credentials(authKey, clientId, clientSecret);
        if (!basic.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            throw new IllegalStateException(
                    "GIGACHAT_AUTH_KEY содержит пробелы или не-ASCII символы. Ключ — base64, "
                            + "то есть латиница, цифры, «+», «/» и «=»: похоже, при копировании "
                            + "из кабинета прихватилось лишнее.");
        }
        if (scope == null || scope.isBlank()) {
            throw new IllegalStateException(
                    "GIGACHAT_SCOPE пуст. Область доступа — одна из GIGACHAT_API_PERS, "
                            + "GIGACHAT_API_B2B, GIGACHAT_API_CORP; какая — зависит от договора с Сбером.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "GIGACHAT_MODEL пуст: ожидается GigaChat, GigaChat-Pro или GigaChat-Max.");
        }

        var http = HttpClient.newBuilder().connectTimeout(timeout);
        if (caBundle != null && !caBundle.isBlank()) {
            http.sslContext(TrustedCertificates.withExtraRoots(Path.of(caBundle.strip())));
        }
        return new GigaChatAuth(http.build(), URI.create(authUrl), json, basic, scope, timeout);
    }

    public URI completionsUrl() {
        return URI.create(apiRoot() + GigaChatHttp.COMPLETIONS);
    }

    public URI embeddingsUrl() {
        return URI.create(apiRoot() + GigaChatEmbeddings.PATH);
    }

    private String apiRoot() {
        var root = apiUrl == null ? GigaChatHttp.CLOUD_API : apiUrl.strip();
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }
}
