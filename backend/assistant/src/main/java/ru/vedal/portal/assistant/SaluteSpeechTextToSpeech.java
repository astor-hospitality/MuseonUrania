package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Озвучивание через SaluteSpeech (Сбер): синхронный REST-синтез.
 *
 * <p><b>Как ходим.</b> {@code POST /rest/v1/text:synthesize?format=…&voice=…}
 * с текстом в теле и токеном из {@link GigaChatAuth} — OAuth-дверь у Сбера
 * одна на GigaChat и SaluteSpeech, разнятся ключ и область доступа.
 * Ответ двери — сами байты звука в запрошенном формате. Тело запроса —
 * {@code application/text} (SSML-разметку портал не шлёт: текст ответа
 * ассистента — обычный текст, и в нём могут встретиться угловые скобки).
 *
 * <p><b>Предел.</b> Дверь принимает до 4 000 символов за запрос, включая
 * пробелы (документация Сбера). Ответ ассистента может быть длиннее
 * (контроллер пускает до 6 000), поэтому текст режется на части по словам,
 * части озвучиваются по очереди, а PCM склеивается в один WAV — тот же
 * приём, что у {@link SpeechKit}.
 *
 * <p><b>Что отдаём.</b> Всегда WAV: при {@code wav16} дверь присылает
 * заголовок сама, и из него берётся частота; при {@code pcm16} — сырой
 * PCM, и частота известна по суффиксу голоса ({@code Nec_24000} — 24 кГц).
 * Заголовок в обоих случаях пишется заново, чтобы склейка частей была
 * одним файлом, а не двумя RIFF подряд.
 *
 * <p><b>Что не хранится.</b> Как и у Яндекса: ни текст, ни звук,
 * ни тело ошибки провайдера не попадают в журнал. На 401 токен
 * обновляется и запрос повторяется один раз.
 */
public final class SaluteSpeechTextToSpeech implements TextToSpeech {

    private static final Logger log = LoggerFactory.getLogger(SaluteSpeechTextToSpeech.class);

    public static final String PATH = "/rest/v1/text:synthesize";
    public static final String WAV16 = "wav16";
    public static final String PCM16 = "pcm16";

    /** Документированный предел двери — 4 000 символов; с запасом на счёт по-другому. */
    static final int PART_LIMIT = 3900;

    private static final String UNAVAILABLE = "Голос временно недоступен";

    private final GigaChatAuth auth;
    private final URI url;
    private final String voice;
    private final String format;
    private final int voiceRate;
    private final Duration timeout;

    public SaluteSpeechTextToSpeech(GigaChatAuth auth, URI synthesizeUrl, String voice, String format,
                                    Duration timeout) {
        this.auth = auth;
        this.voice = voice;
        this.format = format;
        this.voiceRate = rateOf(voice);
        this.timeout = timeout;
        this.url = URI.create(synthesizeUrl + "?format=" + URLEncoder.encode(format, StandardCharsets.UTF_8)
                + "&voice=" + URLEncoder.encode(voice, StandardCharsets.UTF_8));
    }

    /** Ключ проверен на старте; раз объект есть — провайдер настроен. */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public byte[] synthesize(String text) {
        var pcm = new ByteArrayOutputStream();
        int rate = voiceRate;
        for (var part : parts(text)) {
            var audio = exchange(part);
            if (WAV16.equals(format)) {
                var wav = Wav.parse(audio);
                rate = wav.rate();
                pcm.writeBytes(wav.pcm());
            } else {
                pcm.writeBytes(audio);
            }
        }
        return Wav.of(pcm.toByteArray(), rate);
    }

    /**
     * Части не длиннее предела, по возможности — по границе слова.
     * Считаются символы UTF-16, как считает {@link String#length()}:
     * чем именно считает дверь, документация не уточняет, и запас
     * в {@link #PART_LIMIT} — на эту разницу.
     */
    static List<String> parts(String text) {
        var parts = new ArrayList<String>();
        var rest = text.strip();
        while (rest.length() > PART_LIMIT) {
            int cut = rest.lastIndexOf(' ', PART_LIMIT);
            if (cut < PART_LIMIT / 2) cut = PART_LIMIT;
            // Не разрывать суррогатную пару посередине.
            if (Character.isHighSurrogate(rest.charAt(cut - 1))) cut--;
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) parts.add(rest);
        return parts;
    }

    private byte[] exchange(String text) {
        try {
            var response = send(text, auth.token());
            if (response.statusCode() == 401) {
                log.info("SaluteSpeech ответил 401, обновляю токен и повторяю запрос");
                auth.invalidate();
                response = send(text, auth.token());
            }
            if (response.statusCode() != 200) {
                log.warn("SaluteSpeech ответил {}", response.statusCode());
                throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, UNAVAILABLE);
        } catch (IOException e) {
            log.warn("SaluteSpeech недоступен: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
        } catch (IllegalStateException e) {
            // Обмен ключа на токен не удался: причина — в журнале GigaChatAuth
            // без ключа; посетителю — тот же нейтральный текст.
            log.warn("SaluteSpeech: токен не получен: {}", e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
        }
    }

    private HttpResponse<byte[]> send(String text, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/text")
                .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8))
                .build();
        return auth.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Частота по суффиксу голоса: {@code Nec_24000} — 24 000; без суффикса — 24 000. */
    static int rateOf(String voice) {
        int at = voice.lastIndexOf('_');
        if (at < 0) return 24000;
        try {
            return Integer.parseInt(voice.substring(at + 1));
        } catch (NumberFormatException e) {
            return 24000;
        }
    }

    /** RIFF/WAVE: разобрать ответ двери и собрать свой. PCM 16 бит, моно. */
    record Wav(int rate, byte[] pcm) {

        static Wav parse(byte[] bytes) {
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 12 || !"RIFF".equals(ascii(bytes, 0)) || !"WAVE".equals(ascii(bytes, 8))) {
                log.warn("SaluteSpeech прислал не WAV ({} байт)", bytes.length);
                throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
            }
            int rate = 0;
            int at = 12;
            while (at + 8 <= bytes.length) {
                var id = ascii(bytes, at);
                int size = buffer.getInt(at + 4);
                int body = at + 8;
                if (size < 0 || body > bytes.length) break;
                if ("fmt ".equals(id) && size >= 16) {
                    rate = buffer.getInt(body + 4);
                } else if ("data".equals(id)) {
                    int end = Math.min(body + size, bytes.length);
                    if (rate <= 0) break;
                    var pcm = new byte[end - body];
                    System.arraycopy(bytes, body, pcm, 0, pcm.length);
                    return new Wav(rate, pcm);
                }
                at = body + size + (size & 1);
            }
            log.warn("SaluteSpeech прислал WAV без fmt/data ({} байт)", bytes.length);
            throw new ResponseStatusException(BAD_GATEWAY, UNAVAILABLE);
        }

        static byte[] of(byte[] pcm, int rate) {
            return ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                    .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                    .putShort((short) 2).putShort((short) 16)
                    .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm).array();
        }

        private static String ascii(byte[] bytes, int at) {
            return at + 4 <= bytes.length ? new String(bytes, at, 4, StandardCharsets.US_ASCII) : "";
        }
    }
}
