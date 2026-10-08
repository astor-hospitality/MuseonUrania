package ru.vedal.portal.assistant;

import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Распознавание речи моделью Whisper за дверью Cloud.ru Foundation Models:
 * {@code POST /audio/transcriptions} в схеме OpenAI.
 *
 * <p>Та же работа, что у {@link SpeechKitSpeechToText}, другая дверь — и тот
 * же ключ, что у генерации ({@code CLOUDRU_API_KEY}), в {@code Authorization:
 * Bearer} как есть, без обмена на токен. Запрос — {@code multipart/form-data}:
 * поле {@code file} со звуком, {@code model}, {@code language} и
 * {@code response_format=json}; ответ — {@code {"text": "..."}}.
 *
 * <p>Фронт присылает голый PCM16 16 кГц, а дверь ждёт файл в контейнере,
 * который она узнает по заголовку, — поэтому перед отправкой звук
 * заворачивается в WAV тем же {@link SpeechKit#wav}, которым портал отдаёт
 * синтез. Дверь принимает до 25 МБ; 30 секунд PCM16 — меньше мегабайта,
 * но проверка здесь всё равно есть, чтобы отказ на большем звуке был
 * нашим (400), а не чужим (502).
 *
 * <p>Как и у SpeechKit, запросы эфемерны: ни звук, ни текст, ни тела
 * ошибок провайдера в журнал и хранилище не попадают.
 */
public final class CloudRuWhisperSpeechToText implements SpeechToText {

    public static final String PATH = "/audio/transcriptions";

    /** Предел двери на размер файла, байт. */
    static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    private static final String UNAVAILABLE = "Голос временно недоступен";

    private final URI url;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final String language;
    private final Duration timeout;

    public CloudRuWhisperSpeechToText(URI url, ObjectMapper json, String apiKey, String model,
                                      String language, Duration timeout) {
        this.url = url;
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.language = language;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public String model() {
        return model;
    }

    @Override
    public boolean available() {
        return !apiKey.isBlank();
    }

    @Override
    public String recognize(byte[] pcm) {
        if (!available()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, UNAVAILABLE);
        var wav = SpeechKit.wav(pcm);
        if (wav.length > MAX_FILE_BYTES)
            throw new ResponseStatusException(BAD_REQUEST, "Запишите до 30 секунд речи");
        var boundary = "----vedal-" + UUID.randomUUID();
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, wav)))
                .build();
        byte[] body;
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            // Тело ошибки не читаем и не журналируем: там может быть что угодно,
            // а посетителю всё равно нужен только факт — голос сейчас не работает.
            if (response.statusCode() != 200) throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
            body = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, UNAVAILABLE);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
        }
        try {
            var text = json.readTree(body).path("text");
            if (!text.isString()) throw new IllegalStateException("нет поля text");
            return text.asString().strip();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(BAD_GATEWAY, "Не удалось разобрать ответ распознавания");
        }
    }

    /** Тело {@code multipart/form-data}: текстовые поля и файл, по RFC 7578. */
    private byte[] multipart(String boundary, byte[] wav) {
        var out = new ByteArrayOutputStream(wav.length + 512);
        field(out, boundary, "model", model);
        if (language != null && !language.isBlank()) field(out, boundary, "language", language);
        field(out, boundary, "response_format", "json");
        ascii(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        out.writeBytes(wav);
        ascii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        ascii(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        ascii(out, "\r\n");
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
