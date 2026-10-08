package ru.vedal.portal.assistant;

/**
 * Распознавание речи: моно PCM16 16 кГц на входе, текст на выходе.
 *
 * <p>Своё лицо у этой работы потому, что дверей две — SpeechKit Яндекса
 * ({@link SpeechKitSpeechToText}) и Whisper за OpenAI-совместимой дверью
 * Cloud.ru ({@link CloudRuWhisperSpeechToText}), — а {@link VoiceController}
 * не должен знать, которая из них за ним: правила согласия, лимиты и
 * форма звука у него одни на обе. Выбирает дверь {@code VEDAL_STT_PROVIDER}
 * в {@link AssistantConfig#speechToText}.
 *
 * <p>Синтез речи (TTS) сюда намеренно не входит: у Cloud.ru Foundation
 * Models модели синтеза нет, и озвучивание остаётся на SpeechKit
 * независимо от выбранного распознавания.
 */
public interface SpeechToText {

    /** Настроен ли ключ: без него {@code GET /voice} отвечает {@code available: false}. */
    boolean available();

    /**
     * Текст из звука. Звук — моно PCM16 16 кГц без контейнера, до 30 секунд,
     * как его присылает фронт. Ни звук, ни текст, ни тела ошибок провайдера
     * в журнал и хранилище не попадают.
     *
     * @throws org.springframework.web.server.ResponseStatusException
     *         503 — ключа нет или ожидание прервано; 502 — провайдер
     *         не ответил, ответил не 200 или ответ не разобрать
     */
    String recognize(byte[] pcm);
}
