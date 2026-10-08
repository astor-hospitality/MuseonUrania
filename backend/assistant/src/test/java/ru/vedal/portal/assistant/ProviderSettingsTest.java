package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выбор провайдера модели: настройкой, явно, с отказом на опечатке.
 *
 * <p>Правило то же, что у остальных настроек ассистента: половинчатая
 * конфигурация роняет старт с текстом, называющим переменные, а не
 * работает молча не тем провайдером.
 */
class ProviderSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GigaChatSettings sber(String authKey) {
        return new GigaChatSettings(authKey, "", "", "GIGACHAT_API_PERS", "GigaChat", "Embeddings",
                GigaChatEmbeddings.DEFAULT_DIMENSION, GigaChatAuth.CLOUD_URL, GigaChatHttp.CLOUD_API, "");
    }

    private static CloudRuSettings cloudru(String apiKey) {
        return new CloudRuSettings(apiKey, CloudRuSettings.CLOUD_API, CloudRuSettings.DEFAULT_MODEL,
                CloudRuSettings.DEFAULT_EMBEDDINGS_MODEL, CloudRuEmbeddings.DETECT);
    }

    private static ObjectProvider<GigaChatAuth> authOf(GigaChatSettings settings) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("gigaChatAuth",
                new AssistantConfig().gigaChatAuth(settings, JSON, Duration.ofSeconds(5)));
        return beans.getBeanProvider(GigaChatAuth.class);
    }

    private static LlmEngine engine(String engine, String provider, GigaChatSettings sber,
                                    ObjectProvider<GigaChatAuth> auth, String yandexKey, String yandexModel) {
        return engine(engine, provider, sber, auth, cloudru(""), yandexKey, yandexModel);
    }

    private static LlmEngine engine(String engine, String provider, GigaChatSettings sber,
                                    ObjectProvider<GigaChatAuth> auth, CloudRuSettings cloudru,
                                    String yandexKey, String yandexModel) {
        var none = new DefaultListableBeanFactory().getBeanProvider(VectorSearch.class);
        return new AssistantConfig().llmEngine(null, none, null, JSON, PublicDocuments.HIDDEN,
                sber, auth, cloudru, engine, provider, yandexKey, yandexModel, YandexGptHttp.CLOUD_URL,
                true, 0.2, 600, Duration.ofSeconds(25));
    }

    // Умолчание — Яндекс: стенд, где VEDAL_LLM_PROVIDER не задан, после
    // обновления отвечает тем же, чем отвечал. Старое имя режима принимается.
    @Test
    void byDefaultTheModelIsYandexAndTheOldEngineNameStillWorks() {
        var engine = engine("yandexgpt", "yandex", sber(""), authOf(sber("dGVzdDpzZWNyZXQ=")),
                "test-api-key-ascii", "gpt://каталог-1/yandexgpt-lite/latest");

        assertThat(engine).isInstanceOf(ModelEngine.class);
    }

    @Test
    void gigachatIsChosenByTheProviderSetting() {
        var engine = engine("model", "GigaChat", sber("dGVzdDpzZWNyZXQ="), authOf(sber("dGVzdDpzZWNyZXQ=")),
                "", "");

        assertThat(engine).as("ключ Яндекса при провайдере Сбера не нужен")
                .isInstanceOf(ModelEngine.class);
    }

    // Третий провайдер — той же настройкой; ключи Яндекса и Сбера ему не нужны.
    @Test
    void cloudRuIsChosenByTheProviderSettingWithoutOtherKeys() {
        var engine = engine("model", "CloudRu", sber(""), authOf(sber("x")),
                cloudru("test-cloudru-key"), "", "");

        assertThat(engine).isInstanceOf(ModelEngine.class);
    }

    // Без ключа Cloud.ru провайдер cloudru не поднимается — и говорит, где взять ключ.
    @Test
    void cloudRuWithoutAKeyStopsTheStartupAndNamesTheVariable() {
        assertThatThrownBy(() -> engine("model", "cloudru", sber(""), authOf(sber("x")),
                cloudru(""), "", ""))
                .hasMessageContaining("CLOUDRU_API_KEY")
                .hasMessageContaining("Foundation Models");
    }

    @Test
    void aCloudRuKeyWithStrayCharactersIsRefused() {
        assertThatThrownBy(() -> engine("model", "cloudru", sber(""), authOf(sber("x")),
                cloudru("ключ с кириллицей"), "", ""))
                .hasMessageContaining("CLOUDRU_API_KEY")
                .hasMessageContaining("не-ASCII");
    }

    // Опечатка — отказ, а не тихий откат к Яндексу.
    @Test
    void anUnknownProviderStopsTheStartup() {
        assertThatThrownBy(() -> engine("model", "sber", sber(""), authOf(sber("x")), "k", "gpt://a/b"))
                .hasMessageContaining("sber")
                .hasMessageContaining("VEDAL_LLM_PROVIDER")
                .hasMessageContaining("cloudru");
    }

    // Без ключа Сбера провайдер gigachat не поднимается — и говорит, где взять ключ.
    @Test
    void gigachatWithoutAKeyStopsTheStartupAndNamesTheVariables() {
        assertThatThrownBy(() -> new AssistantConfig().gigaChatAuth(sber(""), JSON, Duration.ofSeconds(5)))
                .hasMessageContaining("GIGACHAT_AUTH_KEY")
                .hasMessageContaining("developers.sber.ru");
    }

    @Test
    void aKeyWithStrayCharactersIsRefused() {
        assertThatThrownBy(() -> new AssistantConfig().gigaChatAuth(sber("ключ с кириллицей"), JSON,
                Duration.ofSeconds(5)))
                .hasMessageContaining("GIGACHAT_AUTH_KEY")
                .hasMessageContaining("не-ASCII");
    }

    // Провайдер Яндекса по-прежнему требует свой ключ — и подсказывает про Сбер.
    @Test
    void yandexWithoutAKeyStillStopsTheStartup() {
        assertThatThrownBy(() -> engine("model", "yandex", sber(""), authOf(sber("x")), "", ""))
                .hasMessageContaining("VEDAL_YANDEXGPT_API_KEY")
                .hasMessageContaining("VEDAL_LLM_PROVIDER=gigachat");
    }

    // Режим поиска: провайдер вообще не трогается, даже если в нём опечатка.
    @Test
    void inSearchModeNoProviderIsTouched() {
        var none = new DefaultListableBeanFactory().getBeanProvider(VectorSearch.class);
        var search = new DeterministicSearchStub();
        var engine = new AssistantConfig().llmEngine(search, none, null, JSON, PublicDocuments.HIDDEN,
                sber(""), new DefaultListableBeanFactory().getBeanProvider(GigaChatAuth.class),
                cloudru(""), "deterministic", "опечатка", "", "", YandexGptHttp.CLOUD_URL, true, 0.2, 600,
                Duration.ofSeconds(25));

        assertThat(engine).isSameAs(search);
    }

    /** Поиск-заглушка: нужен только как объект, который вернётся «как есть». */
    private static final class DeterministicSearchStub extends DeterministicSearch {
        DeterministicSearchStub() {
            super(null, null, null, null, PublicDocuments.HIDDEN);
        }
    }
}
