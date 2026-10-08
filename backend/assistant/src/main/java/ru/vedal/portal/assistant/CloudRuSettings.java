package ru.vedal.portal.assistant;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;

/**
 * Настройки Cloud.ru Evolution Foundation Models, собранные в одном месте.
 *
 * <p>Устройство то же, что у {@link GigaChatSettings}, и по той же причине:
 * поля провайдера лежат одним бином, а проверяются только тогда, когда
 * провайдер выбран, — при {@code VEDAL_LLM_PROVIDER=yandex} пустой ключ
 * Cloud.ru не ошибка.
 *
 * <p>Полей меньше, чем у Сбера: ключ статический (как у Яндекса), без
 * OAuth, без области доступа и без корня Минцифры — двери подписаны
 * обычной цепочкой, и штатного хранилища JVM хватает.
 *
 * @param apiKey          API-ключ из консоли Cloud.ru ({@code CLOUDRU_API_KEY})
 * @param baseUrl         корень API ({@code CLOUDRU_BASE_URL}), без завершающей
 *                        косой черты; пути {@code /chat/completions}
 *                        и {@code /embeddings} — относительно него
 * @param model           модель генерации: {@code GigaChat/GigaChat-2-Max},
 *                        {@code GigaChat/GigaChat-3-Pro} и другие из {@code /models}
 * @param embeddingsModel модель эмбеддингов: {@code ai-sage/Giga-Embeddings-instruct-480M}
 *                        или {@code ai-sage/Giga-Embeddings-instruct-3B}
 * @param embeddingsDimension ожидаемая длина вектора; {@code 0} — узнать
 *                        по первому ответу модели ({@link CloudRuEmbeddings})
 */
public record CloudRuSettings(
        String apiKey,
        String baseUrl,
        String model,
        String embeddingsModel,
        int embeddingsDimension) {

    public static final String PROVIDER = "cloudru";

    /** Корень Foundation Models в облаке Cloud.ru. */
    public static final String CLOUD_API = "https://foundation-models.api.cloud.ru/v1";

    public static final String DEFAULT_MODEL = "GigaChat/GigaChat-2-Max";

    public static final String DEFAULT_EMBEDDINGS_MODEL = "ai-sage/Giga-Embeddings-instruct-480M";

    /** Модель распознавания речи за той же дверью ({@code CLOUDRU_STT_MODEL}). */
    public static final String DEFAULT_STT_MODEL = "openai/whisper-large-v3";

    /** Модель генерации — с проверкой того, что настроено всё нужное. */
    public ChatModel chat(ObjectMapper json, double temperature, int maxTokens, Duration timeout) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "CLOUDRU_MODEL пуст: ожидается идентификатор модели из каталога Cloud.ru, "
                            + "например " + DEFAULT_MODEL + " или GigaChat/GigaChat-3-Pro.");
        }
        return new CloudRuHttp(completionsUrl(), json, checkedKey(), model.strip(),
                temperature, maxTokens, timeout);
    }

    /** Модель эмбеддингов — с той же проверкой ключа. */
    public Embeddings embeddings(ObjectMapper json, Duration timeout) {
        if (embeddingsModel == null || embeddingsModel.isBlank()) {
            throw new IllegalStateException(
                    "CLOUDRU_EMBEDDINGS_MODEL пуст: ожидается " + DEFAULT_EMBEDDINGS_MODEL
                            + " или ai-sage/Giga-Embeddings-instruct-3B.");
        }
        if (embeddingsDimension < 0) {
            throw new IllegalStateException(
                    "CLOUDRU_EMBEDDINGS_DIMENSION отрицательна. Длина вектора — положительное "
                            + "число, а 0 (или пустое значение) значит узнать её по первому ответу модели.");
        }
        return new CloudRuEmbeddings(embeddingsUrl(), json, checkedKey(), embeddingsModel.strip(),
                embeddingsDimension, timeout);
    }

    /**
     * Распознавание речи — Whisper за той же дверью и с тем же ключом.
     *
     * <p>Модель приходит снаружи ({@code CLOUDRU_STT_MODEL}), а не лежит
     * в записи: распознавание выбирается своей настройкой
     * ({@code VEDAL_STT_PROVIDER}), и остальным полям оно не нужно.
     * Отказ без ключа — свой, со словами про речь: сообщение
     * {@link #checkedKey()} советует переключить модель, а здесь
     * переключать нужно распознавание.
     */
    public SpeechToText speechToText(ObjectMapper json, String sttModel, String language, Duration timeout) {
        if (sttModel == null || sttModel.isBlank()) {
            throw new IllegalStateException(
                    "CLOUDRU_STT_MODEL пуст: ожидается идентификатор модели распознавания "
                            + "из каталога Cloud.ru, например " + DEFAULT_STT_MODEL + ".");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("""
                    Провайдер распознавания речи cloudru (VEDAL_STT_PROVIDER), но ключ не задан.
                    Нужна переменная окружения CLOUDRU_API_KEY — API-ключ из консоли \
                    Cloud.ru (console.cloud.ru → Evolution Foundation Models → API-ключи), \
                    тот же, что у генерации. Чтобы распознавать по-прежнему через Яндекс — \
                    VEDAL_STT_PROVIDER=yandex (это умолчание) и VEDAL_SPEECHKIT_API_KEY.""");
        }
        return new CloudRuWhisperSpeechToText(transcriptionsUrl(), json, checkedKey(), sttModel.strip(),
                language, timeout);
    }

    /**
     * Ключ — или отказ на старте с текстом, где его взять.
     *
     * <p>Отказ здесь, а не при первом вопросе посетителя: без ключа модель
     * не ответит ни разу, и узнать об этом лучше при развёртывании, чем
     * из жалобы «ассистент перестал отвечать».
     */
    String checkedKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("""
                    Провайдер модели cloudru, но ключ не задан.
                    Нужна переменная окружения CLOUDRU_API_KEY — API-ключ из консоли \
                    Cloud.ru (console.cloud.ru → Evolution Foundation Models → API-ключи). \
                    Без него ассистент отвечать не сможет; чтобы работать без модели, \
                    поставьте vedal.assistant.engine=search, чтобы отвечать через Яндекс \
                    или Сбер — VEDAL_LLM_PROVIDER=yandex или gigachat.""");
        }
        // Ключ уезжает в заголовок Authorization, а туда пускают только ASCII.
        // Та же проверка, что у ключа Яндекса, и по той же причине: ошибка
        // «invalid header value» приходит из клиента, а не из облака, и по
        // ней не догадаться, что дело в самом ключе.
        var key = apiKey.strip();
        if (!key.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            throw new IllegalStateException(
                    "CLOUDRU_API_KEY содержит пробелы или не-ASCII символы. Ключ Cloud.ru — "
                            + "латиница, цифры и знаки пунктуации: похоже, при копировании "
                            + "из консоли прихватилось лишнее.");
        }
        return key;
    }

    public URI completionsUrl() {
        return URI.create(apiRoot() + CloudRuHttp.COMPLETIONS);
    }

    public URI embeddingsUrl() {
        return URI.create(apiRoot() + CloudRuEmbeddings.PATH);
    }

    public URI transcriptionsUrl() {
        return URI.create(apiRoot() + CloudRuWhisperSpeechToText.PATH);
    }

    private String apiRoot() {
        var root = baseUrl == null || baseUrl.isBlank() ? CLOUD_API : baseUrl.strip();
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }
}
