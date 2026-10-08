package ru.vedal.portal.assistant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.vedal.portal.common.RateLimit;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/assistant/v1/voice")
public class VoiceController {
    // Распознавание — за выбранной дверью (VEDAL_STT_PROVIDER: yandex | cloudru),
    // синтез — всегда SpeechKit: у Cloud.ru модели синтеза нет.
    private final SpeechToText recognizer;
    private final SpeechKit speech;
    private final RateLimit limit = new RateLimit(20, Duration.ofMinutes(10));
    private final Semaphore slots = new Semaphore(3);
    public VoiceController(SpeechToText recognizer, SpeechKit speech) {
        this.recognizer = recognizer;
        this.speech = speech;
    }

    @GetMapping
    public ResponseEntity<?> status() {
        // «Доступен» — когда есть обе половины: посетитель и диктует, и слушает.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("available", recognizer.available() && speech.available()));
    }

    private void enter(HttpServletRequest request) {
        if (!"true".equals(request.getHeader("X-Voice-Consent")))
            throw new ResponseStatusException(BAD_REQUEST, "Нужно согласие на обработку голоса");
        limit.forget();
        if (!limit.allow(request.getRemoteAddr()) || !slots.tryAcquire())
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Попробуйте озвучивание позже");
    }

    @PostMapping(value = "/recognize", consumes = "application/octet-stream")
    public ResponseEntity<?> recognize(HttpServletRequest request) throws java.io.IOException {
        enter(request);
        try {
            // Read a bounded stream, never multipart temp files. 30 s mono PCM16 at 16 kHz.
            byte[] pcm = request.getInputStream().readNBytes(960001);
            if (pcm.length == 0 || pcm.length > 960000 || pcm.length % 2 != 0)
                throw new ResponseStatusException(BAD_REQUEST, "Запишите до 30 секунд речи");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("text", recognizer.recognize(pcm)));
        } finally { slots.release(); }
    }

    public record Speak(String text) {}
    @PostMapping(value = "/synthesize", consumes = "application/json", produces = "audio/wav")
    public ResponseEntity<byte[]> synthesize(@RequestBody Speak body, HttpServletRequest request) {
        if (body.text() == null || body.text().isBlank() || body.text().length() > 6000)
            throw new ResponseStatusException(BAD_REQUEST, "Допустимо до 6000 символов");
        enter(request);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .contentType(MediaType.parseMediaType("audio/wav")).body(speech.synthesize(body.text()));
        } finally { slots.release(); }
    }
}
