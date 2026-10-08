package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Схема OpenAI: тело запроса {@code /chat/completions} и разбор потока SSE.
 *
 * <p><b>Зачем вынесено.</b> Две двери говорят на одном диалекте — GigaChat
 * Сбера ({@link GigaChatHttp}) и Foundation Models Cloud.ru
 * ({@link CloudRuHttp}): {@code model}, {@code messages} с {@code content},
 * {@code temperature}, {@code max_tokens}, а в ответ — строки
 * {@code data: {...}} с приращением в {@code choices[0].delta.content}
 * и {@code data: [DONE]} в конце. Различаются только адрес и способ
 * авторизации, и они остаются в реализациях; то, что одинаково, лежит
 * здесь один раз, чтобы правка разбора потока не делалась дважды.
 *
 * <p>Если дверь ответила не потоком, а одним объектом, текст берётся из
 * {@code choices[0].message.content} — ответ посетителю важнее формы.
 */
final class OpenAiChat {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChat.class);

    private OpenAiChat() {}

    /**
     * Тело запроса. Порядок полей фиксирован ({@link LinkedHashMap}): журнал
     * и тест читают тело глазами, и «role, content» в каждой реплике
     * читается проще.
     */
    static String body(ObjectMapper json, String model, List<ChatModel.Message> messages,
                       double temperature, int maxTokens) {
        var replies = new ArrayList<Map<String, String>>();
        for (var message : messages) {
            var reply = new LinkedHashMap<String, String>();
            reply.put("role", message.role() == ChatModel.Role.SYSTEM ? "system" : "user");
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

    /**
     * Прочитать поток, отдавая приращения.
     *
     * <p>Строка — либо {@code data: <json>}, либо {@code data: [DONE]}, либо
     * пустая (разделитель событий). Разбор мягкий: строка незнакомого вида —
     * повод её пропустить, а не оборвать ответ.
     *
     * @return ответ целиком; склейка отданных кусков равна ему же
     * @throws IllegalStateException на пустом ответе: показать посетителю
     *                               пустой пузырь хуже, чем отдать перечень
     *                               найденного
     */
    static String read(InputStream stream, ObjectMapper json, Consumer<String> onChunk)
            throws java.io.IOException {

        var full = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var payload = line.startsWith("data:") ? line.substring(5).strip() : line.strip();
                if (payload.isEmpty() || payload.startsWith(":")) continue;
                if ("[DONE]".equals(payload)) break;

                var chunk = textOf(json, payload);
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

    private static String textOf(ObjectMapper json, String payload) {
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

    /** Начало тела ошибки — в журнал, чтобы не гадать по коду. */
    static String head(HttpResponse<InputStream> response) {
        try (var body = response.body()) {
            return new String(body.readNBytes(400), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "тело ошибки прочитать не удалось";
        }
    }

    /**
     * Вектор из ответа {@code /embeddings} той же схемы:
     * {@code data[0].embedding}. Длина здесь не проверяется — у каждой
     * реализации своё правило, что с ней делать.
     */
    static float[] embedding(ObjectMapper json, String body) {
        var data = json.readTree(body).path("data");
        var node = data.isArray() && !data.isEmpty() ? data.get(0).path("embedding") : data.path("embedding");
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("Ответ эмбеддингов без вектора: " + head(body));
        }
        var vector = new float[node.size()];
        for (var at = 0; at < vector.length; at++) {
            vector[at] = (float) node.get(at).asDouble();
        }
        return vector;
    }

    static String head(String body) {
        return body == null ? "" : body.length() <= 400 ? body : body.substring(0, 400);
    }
}
