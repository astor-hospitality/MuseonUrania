package ru.vedal.portal.assistant;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cloud.ru Evolution Foundation Models по HTTP: {@code /chat/completions},
 * потоковый режим.
 *
 * <p><b>Та же работа, что у {@link GigaChatHttp}, другая дверь.</b> Схема
 * OpenAI — тело запроса и разбор потока SSE общие, {@link OpenAiChat}.
 * Отличие от Сбера — в авторизации: ключ статический, как у Яндекса,
 * уходит в {@code Authorization: Bearer} как есть, обмена на токен нет,
 * и повторять запрос на 401 незачем — второй раз тот же ключ ответит тем же.
 * Корень Минцифры тоже не нужен: двери Cloud.ru подписаны обычной цепочкой.
 *
 * <p>За одной дверью — несколько семейств моделей (GigaChat Сбера, открытые
 * модели), и идентификатор несёт семейство: {@code GigaChat/GigaChat-2-Max}.
 * Правила ответа и материалы готовит {@link ModelEngine}, и он не знает,
 * какая из дверей за {@link ChatModel}.
 */
public class CloudRuHttp implements ChatModel {

    public static final String COMPLETIONS = "/chat/completions";

    private final URI url;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;

    public CloudRuHttp(URI url, ObjectMapper json, String apiKey, String model,
                       double temperature, int maxTokens, Duration timeout) {
        this.url = url;
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String complete(List<Message> messages, Consumer<String> onChunk) {
        var body = OpenAiChat.body(json, model, messages, temperature, maxTokens);
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Cloud.ru ответил " + response.statusCode() + ": " + OpenAiChat.head(response)
                                + (response.statusCode() == 401 ? " (проверьте CLOUDRU_API_KEY)" : ""));
            }
            return OpenAiChat.read(response.body(), json, onChunk);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа модели прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Модель недоступна: " + e.getMessage(), e);
        }
    }
}
