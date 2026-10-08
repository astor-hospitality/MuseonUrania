package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выбор провайдера распознавания речи ({@code VEDAL_STT_PROVIDER}):
 * настройкой, явно, с отказом на опечатке. Правило то же, что у
 * {@link ProviderSettingsTest}: половинчатая конфигурация роняет старт
 * с текстом, называющим переменные, а не работает молча не тем провайдером.
 */
class SttProviderSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CloudRuSettings cloudru(String apiKey) {
        return new CloudRuSettings(apiKey, CloudRuSettings.CLOUD_API, CloudRuSettings.DEFAULT_MODEL,
                CloudRuSettings.DEFAULT_EMBEDDINGS_MODEL, CloudRuEmbeddings.DETECT);
    }

    private static SpeechToText stt(String provider, String speechKitKey, String cloudruKey, String model) {
        return new AssistantConfig().speechToText(new SpeechKit(speechKitKey), cloudru(cloudruKey), JSON,
                provider, model, "ru", Duration.ofSeconds(30));
    }

    // Умолчание — Яндекс: стенд без VEDAL_STT_PROVIDER после обновления
    // распознаёт тем же, чем распознавал, и ключ Cloud.ru ему не нужен.
    @Test
    void byDefaultSpeechKitRecognizesAndTheCloudRuKeyIsNotNeeded() {
        var stt = stt("yandex", "speechkit-key", "", CloudRuSettings.DEFAULT_STT_MODEL);

        assertThat(stt).isInstanceOf(SpeechKitSpeechToText.class);
        assertThat(stt.available()).isTrue();
    }

    // Без ключа SpeechKit Яндекс не роняет старт — голос просто недоступен,
    // как и до появления переключателя.
    @Test
    void speechKitWithoutAKeyIsSimplyUnavailable() {
        var stt = stt("yandex", "", "", CloudRuSettings.DEFAULT_STT_MODEL);

        assertThat(stt.available()).isFalse();
    }

    @Test
    void cloudRuIsChosenByTheSettingWithoutTheSpeechKitKey() {
        var stt = stt("CloudRu", "", "test-cloudru-key", CloudRuSettings.DEFAULT_STT_MODEL);

        assertThat(stt).isInstanceOf(CloudRuWhisperSpeechToText.class);
        assertThat(stt.available()).isTrue();
        assertThat(((CloudRuWhisperSpeechToText) stt).model()).isEqualTo("openai/whisper-large-v3");
    }

    @Test
    void theWhisperModelComesFromItsOwnVariable() {
        var stt = stt("cloudru", "", "test-cloudru-key", " openai/whisper-large-v3-turbo ");

        assertThat(((CloudRuWhisperSpeechToText) stt).model()).isEqualTo("openai/whisper-large-v3-turbo");
    }

    @Test
    void cloudRuWithoutAKeyStopsTheStartupAndNamesTheVariables() {
        assertThatThrownBy(() -> stt("cloudru", "speechkit-key", "", CloudRuSettings.DEFAULT_STT_MODEL))
                .hasMessageContaining("CLOUDRU_API_KEY")
                .hasMessageContaining("VEDAL_STT_PROVIDER=yandex");
    }

    @Test
    void cloudRuWithoutAModelStopsTheStartup() {
        assertThatThrownBy(() -> stt("cloudru", "", "test-cloudru-key", " "))
                .hasMessageContaining("CLOUDRU_STT_MODEL")
                .hasMessageContaining("openai/whisper-large-v3");
    }

    @Test
    void aCloudRuKeyWithStrayCharactersIsRefused() {
        assertThatThrownBy(() -> stt("cloudru", "", "ключ с кириллицей", CloudRuSettings.DEFAULT_STT_MODEL))
                .hasMessageContaining("CLOUDRU_API_KEY")
                .hasMessageContaining("не-ASCII");
    }

    // Опечатка — отказ, а не тихий откат к Яндексу.
    @Test
    void anUnknownProviderStopsTheStartup() {
        assertThatThrownBy(() -> stt("whisper", "k", "k", CloudRuSettings.DEFAULT_STT_MODEL))
                .hasMessageContaining("whisper")
                .hasMessageContaining("VEDAL_STT_PROVIDER")
                .hasMessageContaining("cloudru");
    }
}
