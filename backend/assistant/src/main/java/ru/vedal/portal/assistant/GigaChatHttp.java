package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * GigaChat по HTTP: {@code /api/v1/chat/completions}, потоковый режим.
 *
 * <p><b>Та же работа, что у {@link YandexGptHttp}, другая дверь.</b> Схема
 * запроса у Сбера OpenAI-подобная: {@code model}, {@code messages} с полем
 * {@code content}, {@code temperature}, {@code max_tokens}. Правила ответа
 * и материалы готовит {@link ModelEngine}, и он не знает, какая из дверей
 * за {@link ChatModel}.
 *
 * <p><b>Как устроен поток.</b> Server-Sent Events: строки {@code data: {...}},
 * и каждая несёт ПРИРАЩЕНИЕ ({@code choices[0].delta.content}), а не ответ
 * целиком — в этом отличие от Яндекса, где строка несёт весь текст на
 * текущий момент. Поток заканчивается строкой {@code data: [DONE]}. Тело
 * и разбор потока общие с {@link CloudRuHttp} — {@link OpenAiChat}: у двух
 * дверей один диалект, различаются адрес и авторизация.
 *
 * <p><b>Токен.</b> Короткоживущий, из {@link GigaChatAuth}. На 401 токен
 * забывается и запрос уходит ещё раз с новым — ровно один раз: второй
 * подряд 401 означает, что дело в ключе, а не в сроке токена.
 */
public class GigaChatHttp implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GigaChatHttp.class);

    /** Корень API GigaChat. Остальное — пути относительно него. */
    public static final String CLOUD_API = "https://gigachat.devices.sberbank.ru/api/v1";

    public static final String COMPLETIONS = "/chat/completions";

    private final GigaChatAuth auth;
    private final URI url;
    private final ObjectMapper json;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;

    public GigaChatHttp(GigaChatAuth auth, URI url, ObjectMapper json, String model,
                        double temperature, int maxTokens, Duration timeout) {
        this.auth = auth;
        this.url = url;
        this.json = json;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
    }

    @Override
    public String complete(List<Message> messages, Consumer<String> onChunk) {
        var body = OpenAiChat.body(json, model, messages, temperature, maxTokens);
        var response = send(body, auth.token());
        if (response.statusCode() == 401) {
            // Токен мог быть отозван раньше срока. Один повтор с новым токеном;
            // отдавший поток 401 читать нечего — закрываем его и идём снова.
            log.info("GigaChat ответил 401, обновляю токен и повторяю запрос");
            close(response);
            auth.invalidate();
            response = send(body, auth.token());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "GigaChat ответил " + response.statusCode() + ": " + OpenAiChat.head(response));
        }
        try {
            return OpenAiChat.read(response.body(), json, onChunk);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Поток GigaChat оборвался: " + e.getMessage(), e);
        }
    }

    private HttpResponse<java.io.InputStream> send(String body, String token) {
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return auth.http().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа модели прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Модель недоступна: " + e.getMessage(), e);
        }
    }

    private static void close(HttpResponse<java.io.InputStream> response) {
        try (var body = response.body()) {
            body.readNBytes(400);
        } catch (java.io.IOException e) {
            log.debug("тело ответа 401 не дочитано: {}", e.toString());
        }
    }
}
