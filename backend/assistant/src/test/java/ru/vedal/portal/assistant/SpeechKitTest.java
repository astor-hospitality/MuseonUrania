package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SpeechKitTest {
    @Test void consentAndAudioLimitsAreCheckedBeforeProvider() throws Exception {
        var speech = new SpeechKit("");
        var controller = new VoiceController(speech, new SpeechKitTextToSpeech(speech));
        var request = new MockHttpServletRequest();
        request.setContent(new byte[32000]);
        assertThatThrownBy(() -> controller.recognize(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        request.addHeader("X-Voice-Consent", "true");
        request.setContent(new byte[960001]);
        assertThatThrownBy(() -> controller.recognize(request)).hasMessageContaining("400");
        request.setContent(new byte[32000]);
        assertThatThrownBy(() -> controller.recognize(request)).hasMessageContaining("503");
    }

    @Test void recognizesPcmAndBuildsPlayableWavWithoutSavingAudio() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new AtomicInteger();
        server.createContext("/stt", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Api-Key test");
            assertThat(exchange.getRequestURI().getQuery()).contains("sampleRateHertz=16000");
            assertThat(exchange.getRequestBody().readAllBytes()).hasSize(3200);
            var bytes = "{\"result\":\"Проверка\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/tts", exchange -> {
            count.incrementAndGet();
            var body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).contains("voice=alena", "format=lpcm");
            exchange.sendResponseHeaders(200, 3200);
            exchange.getResponseBody().write(new byte[3200]); exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var speech = new SpeechKit("test", base + "/stt", base + "/tts");
            assertThat(speech.recognize(new byte[3200])).isEqualTo("Проверка");
            var audio = speech.synthesize("Я".repeat(2000));
            assertThat(count.get()).isEqualTo(2);
            assertThat(audio).hasSize(6444);
            assertThat(new String(audio, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            assertThat(ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(16000);
        } finally { server.stop(0); }
    }
}
