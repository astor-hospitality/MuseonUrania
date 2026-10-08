package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Настройка векторного поиска: половинчатая не поднимается.
 *
 * <p>Правило то же, что у модели генерации: включённый режим без ключа —
 * это портал, который молча работает наполовину. Заметить такое можно
 * только по тому, что ответы стали хуже, а это худший из способов узнать
 * о поломке.
 */
class RagSettingsTest {

    private static final String DOC = "emb://каталог-1/text-search-doc/latest";
    private static final String QUERY = "emb://каталог-1/text-search-query/latest";

    private static Embeddings embeddings(String apiKey, String document, String query) {
        return embeddings("yandex", apiKey, document, query);
    }

    private static Embeddings embeddings(String provider, String apiKey, String document, String query) {
        var config = new AssistantConfig();
        var sber = new GigaChatSettings("dGVzdDpzZWNyZXQ=", "", "", "GIGACHAT_API_PERS", "GigaChat",
                "Embeddings", GigaChatEmbeddings.DEFAULT_DIMENSION, GigaChatAuth.CLOUD_URL,
                GigaChatHttp.CLOUD_API, "");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("gigaChatAuth", config.gigaChatAuth(sber, new ObjectMapper(), Duration.ofSeconds(5)));
        return config.embeddings(new ObjectMapper(), sber, beans.getBeanProvider(GigaChatAuth.class),
                provider, apiKey, document, query, YandexEmbeddings.CLOUD_URL, Duration.ofSeconds(15));
    }

    @Test
    void withKeyAndBothModelsTheEmbeddingsAreConfigured() {
        var configured = embeddings("test-api-key-ascii", DOC, QUERY);

        assertThat(configured.dimension()).isEqualTo(YandexEmbeddings.DIMENSION);
        assertThat(configured.name()).isEqualTo(DOC);
    }

    @Test
    void withoutAKeyTheStartupStopsAndNamesTheVariables() {
        assertThatThrownBy(() -> embeddings("", DOC, QUERY))
                .hasMessageContaining("VEDAL_YANDEXGPT_API_KEY")
                .hasMessageContaining("vedal.assistant.rag.enabled=false");
    }

    // Моделей именно две. Одна вместо пары — это векторы вопросов и документов
    // в разных пространствах, то есть поиск, который работает и врёт.
    @Test
    void oneModelInsteadOfThePairIsRefused() {
        assertThatThrownBy(() -> embeddings("test-api-key-ascii", DOC, ""))
                .hasMessageContaining("VEDAL_RAG_QUERY_MODEL_URI");
    }

    // С чужой схемой дверь облака отвечает 404, и по коду это неотличимо
    // от «нет такой модели». Проверка стоит здесь, а не в облаке.
    @Test
    void anAddressWithoutTheEmbSchemeIsRefused() {
        assertThatThrownBy(() -> embeddings("test-api-key-ascii", "gpt://каталог-1/yandexgpt/latest", QUERY))
                .hasMessageContaining("emb://");
    }

    // Провайдер эмбеддингов — свой: индекс можно оставить на Яндексе, переведя
    // генерацию на Сбер, и наоборот. У Сбера модель одна, пары адресов
    // не нужно — и их отсутствие не отказ.
    @Test
    void gigachatNeedsNoYandexModelPair() {
        var configured = embeddings("gigachat", "", "", "");

        assertThat(configured).isInstanceOf(GigaChatEmbeddings.class);
        assertThat(configured.dimension()).isEqualTo(GigaChatEmbeddings.DEFAULT_DIMENSION);
        assertThat(configured.name()).isEqualTo("gigachat/Embeddings");
    }

    // Опечатка в провайдере — отказ на старте, а не тихий откат к Яндексу.
    @Test
    void anUnknownProviderIsRefusedByName() {
        assertThatThrownBy(() -> embeddings("sber", "", "", ""))
                .hasMessageContaining("sber")
                .hasMessageContaining("yandex")
                .hasMessageContaining("gigachat");
    }
}
