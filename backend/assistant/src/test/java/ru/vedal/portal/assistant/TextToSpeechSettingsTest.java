package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выбор голоса: настройкой, явно, с отказом на опечатке — по тому же
 * правилу, что и провайдер модели ({@link ProviderSettingsTest}).
 */
class TextToSpeechSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static SaluteSpeechSettings salute(String authKey, String scope, String voice, String format) {
        return new SaluteSpeechSettings(authKey, scope, voice, format, GigaChatAuth.CLOUD_URL,
                SaluteSpeechSettings.CLOUD_API, "");
    }

    private static TextToSpeech of(String provider, SaluteSpeechSettings salute) {
        return new AssistantConfig().textToSpeech(new SpeechKit(""), salute, JSON, provider, TIMEOUT);
    }

    // Умолчание — Яндекс, и пустые настройки Сбера ему не мешают.
    @Test
    void byDefaultTheVoiceIsSpeechKitAndEmptySberSettingsAreFine() {
        var voice = of("yandex", salute("", "", "", ""));

        assertThat(voice).isInstanceOf(SpeechKitTextToSpeech.class);
        assertThat(voice.available()).as("без ключа SpeechKit — недоступен, но старт не падает").isFalse();
    }

    @Test
    void saluteIsBuiltFromItsOwnSettings() {
        var voice = of(" Salute ", salute("dGVzdDpzZWNyZXQ=", "SALUTE_SPEECH_B2B", "Bys_24000", "WAV16"));

        assertThat(voice).isInstanceOf(SaluteSpeechTextToSpeech.class);
        assertThat(voice.available()).isTrue();
    }

    @Test
    void aTypoInTheProviderFailsAtStartup() {
        assertThatThrownBy(() -> of("sber", salute("", "", "", "")))
                .hasMessageContaining("VEDAL_TTS_PROVIDER")
                .hasMessageContaining("salute");
    }

    // Половинчатая настройка роняет старт с именами переменных.
    @Test
    void saluteWithoutAKeyScopeOrFormatNamesTheVariable() {
        assertThatThrownBy(() -> of("salute", salute("", "SALUTE_SPEECH_PERS", "Nec_24000", "wav16")))
                .hasMessageContaining("SALUTE_AUTH_KEY")
                .hasMessageContaining("не GIGACHAT_AUTH_KEY");
        assertThatThrownBy(() -> of("salute", salute("ключ", "SALUTE_SPEECH_PERS", "Nec_24000", "wav16")))
                .hasMessageContaining("SALUTE_AUTH_KEY содержит");
        assertThatThrownBy(() -> of("salute", salute("abc==", "GIGACHAT_API_PERS", "Nec_24000", "wav16")))
                .hasMessageContaining("SALUTE_SCOPE");
        assertThatThrownBy(() -> of("salute", salute("abc==", "SALUTE_SPEECH_PERS", "", "wav16")))
                .hasMessageContaining("SALUTE_TTS_VOICE");
        assertThatThrownBy(() -> of("salute", salute("abc==", "SALUTE_SPEECH_PERS", "Nec_24000", "opus")))
                .hasMessageContaining("SALUTE_TTS_FORMAT")
                .hasMessageContaining("pcm16");
    }

    @Test
    void theSynthesisUrlIsBuiltFromTheApiRoot() {
        assertThat(salute("", "", "", "").synthesizeUrl())
                .hasToString("https://smartspeech.sber.ru/rest/v1/text:synthesize");
        assertThat(new SaluteSpeechSettings("", "", "", "", "", "http://127.0.0.1:1/", "").synthesizeUrl())
                .hasToString("http://127.0.0.1:1/rest/v1/text:synthesize");
    }
}
