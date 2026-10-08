package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GigaChat по HTTP: {@code /api/v1/chat/completions}, потоковый режим.
 *
 * <p><b>Та же работа, что у {@link YandexGptHttp}, другая дверь.</b> Схема
 * запроса у Сбера OpenAI-подобная: {@code model}, {@code messages} с полем
 * {@code content}, {@code temperature}, {@code max_tokens}. Правила ответа
 * и материалы готовит {@link ModelEngine}, и он не знает, какая из двух
 * дверей за {@link ChatModel}.
 *
 * <p><b>Как устроен поток.</b> Server-Sent Events: строки {@code data: {...}},
 * и каждая несёт ПРИРАЩЕНИЕ ({@code choices[0].delta.content}), а не ответ
 * целиком — в этом отличие от Яндекса, где строка несёт весь текст на
 * текущий момент. Поток заканчивается строкой {@code data: [DONE]}.
 * Если дверь вдруг ответила не потоком, а одним объектом, текст берётся
 * из {@code choices[0].message.content} — ответ посетителю важнее формы.
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
        var body = body(messages);
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
                    "GigaChat ответил " + response.statusCode() + ": " + head(response));
        }
        try {
            return read(response, onChunk);
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

    /**
     * Прочитать поток, отдавая приращения.
     *
     * <p>Строка — либо {@code data: <json>}, либо {@code data: [DONE]}, либо
     * пустая (разделитель событий). Разбор мягкий: строка незнакомого вида —
     * повод её пропустить, а не оборвать ответ.
     */
    private String read(HttpResponse<java.io.InputStream> response, Consumer<String> onChunk)
            throws java.io.IOException {

        var full = new StringBuilder();
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                var payload = line.startsWith("data:") ? line.substring(5).strip() : line.strip();
                if (payload.isEmpty() || payload.startsWith(":")) continue;
                if ("[DONE]".equals(payload)) break;

                var chunk = textOf(payload);
                if (chunk == null || chunk.isEmpty()) continue;
                full.append(chunk);
                onChunk.accept(chunk);
            }
        }

        if (full.isEmpty()) {
            throw new IllegalStateException("Модель вернула пустой ответ");
        }
        return full.toString();
    }

    private String textOf(String payload) {
        try {
            var choices = json.readTree(payload).path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            var choice = choices.get(0);
            var delta = choice.path("delta").path("content");
            if (delta.isString()) return delta.asString();
            var whole = choice.path("message").path("content");
            return whole.isString() ? whole.asString() : null;
        } catch (RuntimeException e) {
            log.debug("строка потока модели не разобрана: {}", e.toString());
            return null;
        }
    }

    private String body(List<Message> messages) {
        // Порядок полей фиксирован (LinkedHashMap): журнал и тест читают
        // тело глазами, и «role, content» в каждой реплике читается проще.
        var replies = new ArrayList<Map<String, String>>();
        for (var message : messages) {
            var reply = new LinkedHashMap<String, String>();
            reply.put("role", message.role() == Role.SYSTEM ? "system" : "user");
            reply.put("content", message.text());
            replies.add(reply);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", replies);
        body.put("stream", true);
        // Ответ по материалам, а не сочинение — та же причина,
        // что и у YandexGPT: ниже температура — ближе к тексту.
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        return json.writeValueAsString(body);
    }

    private static void close(HttpResponse<java.io.InputStream> response) {
        try (var body = response.body()) {
            body.readNBytes(400);
        } catch (java.io.IOException e) {
            log.debug("тело ответа 401 не дочитано: {}", e.toString());
        }
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
