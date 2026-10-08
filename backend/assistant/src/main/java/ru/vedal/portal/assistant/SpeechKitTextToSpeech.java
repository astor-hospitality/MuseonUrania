package ru.vedal.portal.assistant;

/**
 * Озвучивание через Yandex SpeechKit — то, что было, за портом
 * {@link TextToSpeech}.
 *
 * <p>Сам {@link SpeechKit} не тронут: он по-прежнему и распознаёт,
 * и озвучивает, и у него один ключ на обе двери. Здесь — только
 * переходник, чтобы контроллер не знал, чей голос звучит.
 */
public final class SpeechKitTextToSpeech implements TextToSpeech {

    private final SpeechKit speech;

    public SpeechKitTextToSpeech(SpeechKit speech) {
        this.speech = speech;
    }

    @Override
    public boolean available() {
        return speech.available();
    }

    @Override
    public byte[] synthesize(String text) {
        return speech.synthesize(text);
    }
}
