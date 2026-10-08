package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * YandexGPT по HTTP: Foundation Models API, режим потоковой генерации.
 *
 * <p><b>Почему поток, а не обычный ответ.</b> Модель отвечает за секунды,
 * и всё это время посетитель смотрит в окно. В потоковом режиме первые слова
 * приходят через полсекунды, и виджет показывает их сразу — ради этого
 * в разговоре и заведено событие `draft`.
 *
 * <p><b>Как устроен поток.</b> Дверь отдаёт JSON-объекты построчно, и каждый
 * несёт ответ ЦЕЛИКОМ на текущий момент, а не приращение. Поэтому кусок
 * для показа считается вычитанием: пришедшее минус уже отданное. Отдать
 * строку как есть значило бы показать посетителю ответ, растущий копиями
 * самого себя.
 *
 * <p><b>Ключ.</b> Api-Key сервисного аккаунта — статический, не требует
 * обмена на IAM-токен и не протухает через двенадцать часов. Живёт
 * в окружении (`VEDAL_YANDEX_API_KEY`), в репозиторий не попадает.
 */
public class YandexGptHttp implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(YandexGptHttp.class);

    /**
     * Дверь Foundation Models в облаке.
     *
     * <p>Адрес приходит конструктором, а не берётся отсюда напрямую, — ради
     * теста: он поднимает свой сервер на случайном порту и говорит с ним
     * тем же кодом, что работает в проде. Настройкой приложения адрес при
     * этом не стал: в работе он один и меняться ему незачем.
     */
    public static final String CLOUD_URL =
            "https://llm.api.cloud.yandex.net/foundationModels/v1/completion";

    public static final URI CLOUD = URI.create(CLOUD_URL);

    private final URI url;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;
    /**
     * Адрес модели целиком: {@code gpt://<каталог>/yandexgpt-lite/latest}.
     *
     * <p>Одной строкой, а не парой «каталог + имя»: в таком виде его выдаёт
     * консоль облака, в таком виде он лежит в окружении стенда, и собирать
     * его из кусков значит завести второй способ ошибиться. Каталог внутри
     * адреса — он же определяет, чей счёт оплачивает запрос.
     */
    private final String modelUri;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;

    public YandexGptHttp(URI url, ObjectMapper json, String apiKey, String modelUri,
                         double temperature, int maxTokens, Duration timeout) {
        this.url = url;
        this.json = json;
        this.apiKey = apiKey;
        this.modelUri = modelUri;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String complete(List<Message> messages, Consumer<String> onChunk) {
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                // Api-Key, а не Bearer: у статического ключа своя схема.
                // С Bearer дверь отвечает 401, и по тексту ошибки это
                // выглядит как «ключ неверный», хотя неверна схема.
                .header("Authorization", "Api-Key " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body(messages), StandardCharsets.UTF_8))
                .build();

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "YandexGPT ответил " + response.statusCode() + ": " + head(response));
            }
            return read(response, onChunk);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа модели прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Модель недоступна: " + e.getMessage(), e);
        }
    }

    /**
     * Прочитать поток, отдавая приращения.
     *
     * <p>Каждая строка — полный ответ на текущий момент. Отданное запоминаем
     * и шлём только хвост; так склейка кусков равна итоговому тексту,
     * а виджет дописывает ответ, а не показывает его заново с каждой строкой.
     */
    private String read(HttpResponse<java.io.InputStream> response, Consumer<String> onChunk)
            throws java.io.IOException {

        var full = "";
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                var text = textOf(line);
                if (text == null || text.length() < full.length()) continue;

                var chunk = text.substring(full.length());
                full = text;
                if (!chunk.isEmpty()) onChunk.accept(chunk);
            }
        }

        if (full.isBlank()) {
            throw new IllegalStateException("Модель вернула пустой ответ");
        }
        return full;
    }

    /**
     * Текст из строки потока.
     *
     * <p>Разбор мягкий: строка незнакомого вида — повод её пропустить,
     * а не оборвать ответ. Дверь присылает и служебные объекты
     * (например, с причиной завершения), и они не обязаны содержать текст.
     */
    private String textOf(String line) {
        try {
            var root = json.readTree(line);
            var alternatives = root.path("result").path("alternatives");
            if (!alternatives.isArray() || alternatives.isEmpty()) return null;
            return alternatives.get(0).path("message").path("text").asString();
        } catch (RuntimeException e) {
            log.debug("строка потока модели не разобрана: {}", e.toString());
            return null;
        }
    }

    private String body(List<Message> messages) {
        var replies = new ArrayList<Map<String, String>>();
        for (var message : messages) {
            replies.add(Map.of(
                    "role", message.role() == Role.SYSTEM ? "system" : "user",
                    "text", message.text()));
        }

        return json.writeValueAsString(Map.of(
                "modelUri", modelUri,
                "completionOptions", Map.of(
                        "stream", true,
                        // Ответ по материалам, а не сочинение: низкая
                        // температура держит модель ближе к тексту, который
                        // ей дали. Выше — начинает пересказывать своими
                        // словами и добавлять то, чего в материалах нет.
                        "temperature", temperature,
                        "maxTokens", String.valueOf(maxTokens)),
                "messages", replies));
    }

    /** Начало тела ошибки — в журнал, чтобы не гадать по коду. */
    private static String head(HttpResponse<java.io.InputStream> response) {
        try (var body = response.body()) {
            return new String(body.readNBytes(400), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "тело ошибки прочитать не удалось";
        }
    }
}
