package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Озвучивание через SaluteSpeech: что уходит в дверь Сбера и что
 * получает посетитель.
 *
 * <p>Обе двери — OAuth и синтез — подняты тут же, на случайном порту:
 * наружу тест не ходит. Звук подставной: WAV с заголовком при
 * {@code wav16}, голый PCM при {@code pcm16}.
 */
class SaluteSpeechTextToSpeechTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicInteger syntheses = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private final List<String> bearers = new ArrayList<>();
    private final List<String> contentTypes = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/oauth", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var token = "token-" + exchanges.incrementAndGet();
            reply(exchange, 200, ("{\"access_token\":\"" + token + "\",\"expires_at\":"
                    + (System.currentTimeMillis() + 1_800_000) + "}").getBytes(StandardCharsets.UTF_8));
        });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Дверь синтеза: первые {@code unauthorized} ответов — 401, дальше звук. */
    private void synthesizing(int unauthorized, byte[] audio) {
        server.createContext("/rest/v1/text:synthesize", exchange -> {
            var n = syntheses.incrementAndGet();
            queries.add(exchange.getRequestURI().getQuery());
            bearers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (n <= unauthorized) {
                reply(exchange, 401, "{\"code\":401}".getBytes(StandardCharsets.UTF_8));
            } else {
                reply(exchange, 200, audio);
            }
        });
        server.start();
    }

    private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SaluteSpeechTextToSpeech speech(String voice, String format) {
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var auth = new GigaChatAuth(HttpClient.newHttpClient(), URI.create(base + "/api/v2/oauth"), json,
                "dGVzdDpzZWNyZXQ=", "SALUTE_SPEECH_PERS", Duration.ofSeconds(5));
        return new SaluteSpeechTextToSpeech(auth, URI.create(base + SaluteSpeechTextToSpeech.PATH),
                voice, format, Duration.ofSeconds(5));
    }

    /** WAV 24 кГц с одним лишним чанком перед данными — как могла бы прислать дверь. */
    private static byte[] wav(int rate, int pcmBytes) {
        var list = "LIST".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(44 + 12 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 12 + pcmBytes)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put(list).putInt(4).put("INFO".getBytes(StandardCharsets.US_ASCII))
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcmBytes).put(new byte[pcmBytes]).array();
    }

    // Форма запроса — та, которую требует дверь: формат и голос в адресе,
    // Bearer-токен из OAuth, текст телом как есть.
    @Test
    void theRequestCarriesFormatVoiceBearerAndPlainText() {
        synthesizing(0, wav(24000, 4800));

        var audio = speech("Nec_24000", "wav16").synthesize("Проверка связи");

        assertThat(queries).containsExactly("format=wav16&voice=Nec_24000");
        assertThat(bearers).containsExactly("Bearer token-1");
        assertThat(contentTypes).containsExactly("application/text");
        assertThat(bodies).containsExactly("Проверка связи");
        // Заголовок переписан: 44 байта, PCM без чужого LIST-чанка, частота из ответа.
        assertThat(audio).hasSize(44 + 4800);
        assertThat(new String(audio, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(24000);
        assertThat(ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getInt(40)).isEqualTo(4800);
    }

    // Сырой PCM получает заголовок с частотой из суффикса голоса.
    @Test
    void rawPcmIsWrappedIntoWavAtTheVoiceRate() {
        synthesizing(0, new byte[1600]);

        var audio = speech("Bys_8000", "pcm16").synthesize("Проверка");

        assertThat(queries).containsExactly("format=pcm16&voice=Bys_8000");
        assertThat(audio).hasSize(44 + 1600);
        assertThat(ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(8000);
        assertThat(ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getInt(28)).isEqualTo(16000);
    }

    // Длинный ответ режется по словам на части не длиннее предела двери,
    // и звук склеивается в один файл.
    @Test
    void longTextIsSplitByWordsAndTheAudioIsJoined() {
        synthesizing(0, wav(24000, 2400));
        var text = ("слово ".repeat(1000)).strip(); // 5 999 символов

        var audio = speech("Nec_24000", "wav16").synthesize(text);

        assertThat(syntheses.get()).isEqualTo(2);
        assertThat(bodies).allSatisfy(part -> {
            assertThat(part.length()).isLessThanOrEqualTo(SaluteSpeechTextToSpeech.PART_LIMIT);
            assertThat(part).doesNotStartWith(" ").doesNotEndWith(" ");
        });
        assertThat(String.join(" ", bodies)).isEqualTo(text);
        assertThat(audio).hasSize(44 + 4800);
        assertThat(exchanges.get()).as("токен один на обе части").isEqualTo(1);
    }

    // Протухший токен: дверь ответила 401 — токен обновляется, запрос
    // повторяется один раз. Два 401 подряд — отказ.
    @Test
    void a401RefreshesTheTokenAndRetriesOnce() {
        synthesizing(1, wav(24000, 480));

        speech("Nec_24000", "wav16").synthesize("Ещё раз");

        assertThat(bearers).containsExactly("Bearer token-1", "Bearer token-2");
        assertThat(exchanges.get()).isEqualTo(2);
    }

    @Test
    void twoRefusalsInARowAreAnError() {
        synthesizing(2, wav(24000, 480));

        assertThatThrownBy(() -> speech("Nec_24000", "wav16").synthesize("Ещё раз"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502")
                .hasMessageContaining("Голос временно недоступен");
        assertThat(syntheses.get()).isEqualTo(2);
    }

    // Не WAV в ответе на wav16 — отказ, а не битый файл посетителю.
    @Test
    void aNonWavAnswerIsAnError() {
        synthesizing(0, "<html>oops</html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> speech("Nec_24000", "wav16").synthesize("Проверка"))
                .hasMessageContaining("502");
    }

    @Test
    void partsAndRatesAreComputedAsDocumented() {
        assertThat(SaluteSpeechTextToSpeech.parts("  коротко  ")).containsExactly("коротко");
        assertThat(SaluteSpeechTextToSpeech.parts("я".repeat(8000)))
                .as("без пробелов режется жёстко по пределу")
                .extracting(String::length).containsExactly(3900, 3900, 200);
        assertThat(SaluteSpeechTextToSpeech.rateOf("Nec_24000")).isEqualTo(24000);
        assertThat(SaluteSpeechTextToSpeech.rateOf("Ost_8000")).isEqualTo(8000);
        assertThat(SaluteSpeechTextToSpeech.rateOf("Nec")).isEqualTo(24000);
    }
}
