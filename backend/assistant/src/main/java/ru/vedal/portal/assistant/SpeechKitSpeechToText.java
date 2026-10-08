package ru.vedal.portal.assistant;

/**
 * Распознавание через SpeechKit Яндекса — обёртка над {@link SpeechKit},
 * который по-прежнему умеет и синтез. Сам {@link SpeechKit} не меняется:
 * он остаётся бином с ключом {@code VEDAL_SPEECHKIT_API_KEY}, а здесь
 * из него берётся только распознавание, чтобы {@link VoiceController}
 * видел одно и то же лицо у обоих провайдеров.
 */
public final class SpeechKitSpeechToText implements SpeechToText {

    private final SpeechKit speechKit;

    public SpeechKitSpeechToText(SpeechKit speechKit) {
        this.speechKit = speechKit;
    }

    @Override
    public boolean available() {
        return speechKit.available();
    }

    @Override
    public String recognize(byte[] pcm) {
        return speechKit.recognize(pcm);
    }
}
