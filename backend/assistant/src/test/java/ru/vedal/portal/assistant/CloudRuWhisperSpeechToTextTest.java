package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Распознавание речи Whisper за дверью Cloud.ru. Устройство то же, что у
 * {@link SpeechKitTest}: сервер свой, наружу тест не ходит. Главное —
 * форма запроса: multipart с WAV-файлом, моделью и языком под ключом Bearer.
 */
class CloudRuWhisperSpeechToTextTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Дверь распознавания: запоминает запрос, отвечает заданным телом. */
    private void answering(int status, String body) {
        server.createContext("/v1" + CloudRuWhisperSpeechToText.PATH, exchange -> {
            calls.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(exchange.getRequestBody().readAllBytes());
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private SpeechToText client(String key, String language, Duration timeout) {
        var settings = new CloudRuSettings(key,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                CloudRuSettings.DEFAULT_MODEL, CloudRuSettings.DEFAULT_EMBEDDINGS_MODEL, CloudRuEmbeddings.DETECT);
        return settings.speechToText(json, CloudRuSettings.DEFAULT_STT_MODEL, language, timeout);
    }

    private SpeechToText client() {
        return client("test-cloudru-key", "ru", Duration.ofSeconds(5));
    }

    @Test
    void theTextComesBackFromAMultipartWavUnderTheBearerKey() {
        answering(200, "{\"text\":\" Проверка связи \"}");
        var pcm = new byte[3200];
        pcm[0] = 7;

        var text = client().recognize(pcm);

        assertThat(text).isEqualTo("Проверка связи");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastAuth.get()).isEqualTo("Bearer test-cloudru-key");
        assertThat(lastContentType.get()).startsWith("multipart/form-data; boundary=");
        var boundary = lastContentType.get().substring("multipart/form-data; boundary=".length());

        var body = new String(lastBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(body)
                .startsWith("--" + boundary + "\r\n")
                .endsWith("\r\n--" + boundary + "--\r\n")
                .contains("Content-Disposition: form-data; name=\"model\"\r\n\r\nopenai/whisper-large-v3\r\n")
                .contains("Content-Disposition: form-data; name=\"language\"\r\n\r\nru\r\n")
                .contains("Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
                .contains("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\nRIFF");

        // Файл — тот же WAV, которым портал отдаёт синтез: 44 байта заголовка
        // и звук как есть, 16 кГц, моно, 16 бит.
        int at = body.indexOf("RIFF");
        var wav = ByteBuffer.wrap(lastBody.get(), at, 44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(wav.getInt(at + 24)).isEqualTo(16000);
        assertThat(wav.getInt(at + 40)).isEqualTo(pcm.length);
        assertThat(lastBody.get()[at + 44]).isEqualTo((byte) 7);
        assertThat(body.indexOf("\r\n--" + boundary + "--")).isEqualTo(at + 44 + pcm.length);
    }

    @Test
    void anEmptyLanguageIsNotSentAndTheModelDecidesItself() {
        answering(200, "{\"text\":\"ok\"}");

        client("test-cloudru-key", "", Duration.ofSeconds(5)).recognize(new byte[320]);

        assertThat(new String(lastBody.get(), StandardCharsets.ISO_8859_1)).doesNotContain("name=\"language\"");
    }

    @Test
    void aClientErrorFromTheDoorIsABadGatewayWithoutItsBodyInTheMessage() {
        answering(401, "{\"error\":{\"message\":\"invalid api key: SECRET-DETAILS\"}}");

        assertThatThrownBy(() -> client().recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502")
                .hasMessageNotContaining("SECRET-DETAILS");
    }

    @Test
    void aServerErrorFromTheDoorIsABadGateway() {
        answering(503, "upstream is down");

        assertThatThrownBy(() -> client().recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");
    }

    @Test
    void anAnswerWithoutTextIsABadGatewayToo() {
        answering(200, "{\"segments\":[]}");

        assertThatThrownBy(() -> client().recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502")
                .hasMessageContaining("разобрать");
    }

    @Test
    void brokenJsonIsABadGateway() {
        answering(200, "<html>not json</html>");

        assertThatThrownBy(() -> client().recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");
    }

    @Test
    void aSilentDoorIsABadGatewayAfterTheTimeout() {
        server.createContext("/v1" + CloudRuWhisperSpeechToText.PATH, exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client("test-cloudru-key", "ru", Duration.ofMillis(300)).recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");
    }

    @Test
    void anUnreachableDoorIsABadGateway() {
        var unreachable = new CloudRuWhisperSpeechToText(
                URI.create("http://127.0.0.1:1" + CloudRuWhisperSpeechToText.PATH), json,
                "test-cloudru-key", CloudRuSettings.DEFAULT_STT_MODEL, "ru", Duration.ofSeconds(2));

        assertThatThrownBy(() -> unreachable.recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");
    }

    @Test
    void withoutAKeyNothingIsSentAndTheVoiceIsUnavailable() {
        answering(200, "{\"text\":\"ok\"}");
        var speech = new CloudRuWhisperSpeechToText(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1" + CloudRuWhisperSpeechToText.PATH),
                json, "", CloudRuSettings.DEFAULT_STT_MODEL, "ru", Duration.ofSeconds(5));

        assertThat(speech.available()).isFalse();
        assertThatThrownBy(() -> speech.recognize(new byte[320]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
        assertThat(calls.get()).isZero();
    }

    @Test
    void audioAboveTheDoorsLimitIsRefusedBeforeSending() {
        answering(200, "{\"text\":\"ok\"}");

        assertThatThrownBy(() -> client().recognize(new byte[CloudRuWhisperSpeechToText.MAX_FILE_BYTES]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThat(calls.get()).isZero();
    }

    // Контроллер не знает, какая дверь за ним: согласие и лимиты те же,
    // текст — от Whisper, а статус голоса учитывает обе половины.
    @Test
    void theControllerRecognizesThroughWhisperAndKeepsSynthesisOnSpeechKit() throws Exception {
        answering(200, "{\"text\":\"Проверка\"}");
        var controller = new VoiceController(client(), new SpeechKitTextToSpeech(new SpeechKit("")));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Voice-Consent", "true");
        request.setContent(new byte[3200]);

        var reply = controller.recognize(request);

        assertThat(reply.getBody()).isEqualTo(java.util.Map.of("text", "Проверка"));
        assertThat(controller.status().getBody()).as("без ключа SpeechKit озвучивания нет — голос не доступен")
                .isEqualTo(java.util.Map.of("available", false));
    }
}
