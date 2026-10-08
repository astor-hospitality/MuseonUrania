package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import ru.vedal.portal.catalog.CatalogQuery;
import ru.vedal.portal.common.RateLimit;
import ru.vedal.portal.content.ContentQuery;
import ru.vedal.portal.documents.DocumentQuery;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;

@Configuration
public class AssistantConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfig.class);

    // Свой бюджет, отдельный от форм: разговор с ассистентом не должен
    // отнимать у посетителя право отправить заявку.
    @Bean
    RateLimit assistantRateLimit(@Value("${vedal.assistant.rate-limit.count:20}") int limit,
                                @Value("${vedal.assistant.rate-limit.window:PT10M}") Duration window) {
        return new RateLimit(limit, window);
    }

    /**
     * Показывает ли Ведалина посетителю документы.
     *
     * <p>По умолчанию нет: публичный раздел документов скрыт решением
     * заказчика, и ассистент, забывший об этом при пустом окружении, повёл бы
     * посетителя в раздел, которого на сайте нет. Включается вместе с разделом
     * на фронте — {@code VEDAL_PUBLIC_DOCUMENTS_ENABLED=true}.
     */
    @Bean
    PublicDocuments publicDocuments(
            @Value("${vedal.assistant.public-documents-enabled:false}") boolean enabled) {
        if (!enabled) {
            log.info("Ведалина не показывает посетителю документы "
                    + "(vedal.assistant.public-documents-enabled=false)");
        }
        return new PublicDocuments(enabled);
    }

    /**
     * Настройки GigaChat — одним бином, а не десятком {@code @Value}
     * в каждом потребителе. Проверяются не здесь, а в {@link #gigaChatAuth}:
     * при провайдере {@code yandex} пустой ключ Сбера — не ошибка.
     */
    @Bean
    GigaChatSettings gigaChatSettings(
            @Value("${vedal.assistant.gigachat.auth-key:}") String authKey,
            @Value("${vedal.assistant.gigachat.client-id:}") String clientId,
            @Value("${vedal.assistant.gigachat.client-secret:}") String clientSecret,
            @Value("${vedal.assistant.gigachat.scope:GIGACHAT_API_PERS}") String scope,
            @Value("${vedal.assistant.gigachat.model:GigaChat}") String model,
            @Value("${vedal.assistant.gigachat.embeddings-model:Embeddings}") String embeddingsModel,
            @Value("${vedal.assistant.gigachat.embeddings-dimension:" + GigaChatEmbeddings.DEFAULT_DIMENSION + "}")
            int embeddingsDimension,
            @Value("${vedal.assistant.gigachat.auth-url:" + GigaChatAuth.CLOUD_URL + "}") String authUrl,
            @Value("${vedal.assistant.gigachat.api-url:" + GigaChatHttp.CLOUD_API + "}") String apiUrl,
            @Value("${vedal.assistant.gigachat.ca-bundle:}") String caBundle) {
        return new GigaChatSettings(authKey, clientId, clientSecret, scope, model, embeddingsModel,
                embeddingsDimension, authUrl, apiUrl, caBundle);
    }

    /**
     * Настройки Cloud.ru — тем же образом, что и Сбера. Проверяются
     * в {@link CloudRuSettings#chat} и {@link CloudRuSettings#embeddings},
     * то есть только при выбранном провайдере {@code cloudru}.
     */
    @Bean
    CloudRuSettings cloudRuSettings(
            @Value("${vedal.assistant.cloudru.api-key:}") String apiKey,
            @Value("${vedal.assistant.cloudru.base-url:" + CloudRuSettings.CLOUD_API + "}") String baseUrl,
            @Value("${vedal.assistant.cloudru.model:" + CloudRuSettings.DEFAULT_MODEL + "}") String model,
            @Value("${vedal.assistant.cloudru.embeddings-model:" + CloudRuSettings.DEFAULT_EMBEDDINGS_MODEL + "}")
            String embeddingsModel,
            @Value("${vedal.assistant.cloudru.embeddings-dimension:" + CloudRuEmbeddings.DETECT + "}")
            int embeddingsDimension) {
        return new CloudRuSettings(apiKey, baseUrl, model, embeddingsModel, embeddingsDimension);
    }

    /**
     * Обменник ключа Сбера на токен — один на генерацию и эмбеддинги.
     *
     * <p>{@code @Lazy} обязателен: бин создаётся только тогда, когда его
     * впервые попросят, а просят его только при {@code gigachat} в одном
     * из провайдеров. Иначе портал на Яндексе падал бы на старте из-за
     * отсутствующего ключа Сбера, который ему не нужен.
     */
    @Bean
    @Lazy
    GigaChatAuth gigaChatAuth(GigaChatSettings settings, ObjectMapper json,
                              @Value("${vedal.assistant.model.timeout:PT25S}") Duration timeout) {
        return settings.auth(json, timeout);
    }

    /**
     * Чья дверь распознаёт речь: SpeechKit Яндекса или Whisper за дверью
     * Cloud.ru — {@code VEDAL_STT_PROVIDER}, {@code yandex} по умолчанию.
     *
     * <p>Настройка своя, отдельная от {@code VEDAL_LLM_PROVIDER}, и по той же
     * причине, по которой {@code VEDAL_RAG_PROVIDER} отделён от генерации:
     * голос и модель переезжают в разное время, и стенд, где модель уже
     * на Cloud.ru, а ключ SpeechKit ещё жив, не должен терять голос
     * молча. Умолчание — Яндекс: стенд без этой переменной после обновления
     * распознаёт тем же, чем распознавал.
     *
     * <p>Озвучивание выбирается отдельно — {@link #textToSpeech}.
     *
     * <p>Половинчатая пара «провайдер + ключ» роняет старт с текстом,
     * называющим переменные ({@link CloudRuSettings#speechToText}),
     * а опечатка в провайдере — отказ, а не тихий откат к Яндексу.
     */
    @Bean
    SpeechToText speechToText(SpeechKit speechKit, CloudRuSettings cloudru, ObjectMapper json,
                              @Value("${vedal.assistant.stt.provider:" + YANDEX + "}") String provider,
                              @Value("${vedal.assistant.cloudru.stt-model:" + CloudRuSettings.DEFAULT_STT_MODEL + "}")
                              String sttModel,
                              @Value("${vedal.assistant.stt.language:ru}") String language,
                              @Value("${vedal.assistant.stt.timeout:PT30S}") Duration timeout) {
        var name = provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case YANDEX -> new SpeechKitSpeechToText(speechKit);
            case CloudRuSettings.PROVIDER -> {
                log.info("Ведалина распознаёт речь моделью Cloud.ru {}", sttModel == null ? "" : sttModel.strip());
                yield cloudru.speechToText(json, sttModel, language, timeout);
            }
            default -> throw new IllegalStateException(
                    "Неизвестный провайдер распознавания речи «" + provider + "». VEDAL_STT_PROVIDER "
                            + "принимает yandex (SpeechKit) или cloudru (Whisper за дверью Cloud.ru).");
        };
    }

    /**
     * Настройки SaluteSpeech — тем же образом, что и Сбера для модели.
     * Проверяются в {@link #textToSpeech}, то есть только при
     * {@code VEDAL_TTS_PROVIDER=salute}. Корень Минцифры по умолчанию
     * тот же файл, что у GigaChat: {@code SALUTE_CA_BUNDLE} наследует
     * {@code GIGACHAT_CA_BUNDLE} в application.properties.
     */
    @Bean
    SaluteSpeechSettings saluteSpeechSettings(
            @Value("${vedal.assistant.salute.auth-key:}") String authKey,
            @Value("${vedal.assistant.salute.scope:" + SaluteSpeechSettings.DEFAULT_SCOPE + "}") String scope,
            @Value("${vedal.assistant.salute.tts-voice:" + SaluteSpeechSettings.DEFAULT_VOICE + "}") String voice,
            @Value("${vedal.assistant.salute.tts-format:" + SaluteSpeechSettings.DEFAULT_FORMAT + "}") String format,
            @Value("${vedal.assistant.salute.auth-url:" + GigaChatAuth.CLOUD_URL + "}") String authUrl,
            @Value("${vedal.assistant.salute.api-url:" + SaluteSpeechSettings.CLOUD_API + "}") String apiUrl,
            @Value("${vedal.assistant.salute.ca-bundle:${vedal.assistant.gigachat.ca-bundle:}}") String caBundle) {
        return new SaluteSpeechSettings(authKey, scope, voice, format, authUrl, apiUrl, caBundle);
    }

    /**
     * Чей голос озвучивает ответы: {@code yandex} (SpeechKit, как было)
     * или {@code salute} (SaluteSpeech Сбера).
     *
     * <p>Настройка своя, не {@code VEDAL_LLM_PROVIDER}: модель и голос —
     * разные договоры и разные ключи, и перевести ответы на GigaChat,
     * оставив голос Яндекса, — нормальное состояние. Умолчание — Яндекс,
     * чтобы стенд без переменной звучал тем же, чем звучал. Опечатка —
     * отказ на старте, а не тихий откат.
     *
     * <p>Распознавание речи выбирается отдельно — {@link #speechToText}.
     */
    @Bean
    TextToSpeech textToSpeech(SpeechKit speechKit, SaluteSpeechSettings salute, ObjectMapper json,
                              @Value("${vedal.assistant.tts.provider:" + YANDEX + "}") String provider,
                              @Value("${vedal.assistant.model.timeout:PT25S}") Duration timeout) {
        var name = provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case YANDEX -> new SpeechKitTextToSpeech(speechKit);
            case SaluteSpeechSettings.PROVIDER -> {
                log.info("Ведалина озвучивает ответы голосом SaluteSpeech {} ({})",
                        salute.voice(), salute.format());
                yield salute.textToSpeech(json, timeout);
            }
            default -> throw new IllegalStateException(
                    "Неизвестный провайдер озвучивания «" + provider + "». VEDAL_TTS_PROVIDER "
                            + "принимает yandex или salute.");
        };
    }

    /**
     * Кто отвечает: модель или поиск по словам.
     *
     * <p><b>Почему выбор настройкой, а не наличием ключа.</b> «Есть ключ —
     * работает модель» звучит удобно ровно до первого раза, когда ключ
     * не доехал в окружение: портал молча начинает отвечать перечнем ссылок,
     * и понять это можно только по тому, что ответы вдруг стали суше.
     * Здесь режим объявлен явно, и несобранная пара «режим + ключ» роняет
     * старт с внятным сообщением, а не работает наполовину.
     *
     * <p>Значение по умолчанию — поиск: на машине разработчика и в тестах
     * ключей нет и быть не должно, а чат обязан работать.
     *
     * <p><b>Режим и провайдер — две разные настройки.</b> {@code engine}
     * говорит, отвечает ли модель вообще ({@code model}; старое значение
     * {@code yandexgpt} принимается как то же самое), {@code provider} —
     * чья именно: {@code yandex} (YandexGPT), {@code gigachat} (Sber)
     * или {@code cloudru} (Cloud.ru Foundation Models).
     * Провайдер по умолчанию — Яндекс: так стенд, где переменная не задана,
     * после обновления отвечает тем же, чем отвечал.
     *
     * <p>{@code @Primary} обязателен: {@link DeterministicSearch} сам по себе
     * бин и сам по себе {@link LlmEngine}, поэтому претендентов на место
     * движка двое. Главный — этот: он и решает, кто отвечает. В режиме
     * поиска он возвращает тот же самый объект, так что двух движков
     * в приложении не появляется ни при какой настройке.
     */
    @Bean
    @Primary
    LlmEngine llmEngine(
            DeterministicSearch search,
            ObjectProvider<VectorSearch> vectors,
            JdbcClient jdbc,
            ObjectMapper json,
            PublicDocuments documents,
            GigaChatSettings gigachat,
            ObjectProvider<GigaChatAuth> gigachatAuth,
            CloudRuSettings cloudru,
            @Value("${vedal.assistant.engine:search}") String engine,
            @Value("${vedal.assistant.provider:" + YANDEX + "}") String provider,
            @Value("${vedal.assistant.yandex.api-key:}") String apiKey,
            @Value("${vedal.assistant.yandex.model-uri:}") String modelUri,
            @Value("${vedal.assistant.yandex.endpoint:" + YandexGptHttp.CLOUD_URL + "}") String endpoint,
            @Value("${vedal.assistant.model.fallback:true}") boolean fallback,
            @Value("${vedal.assistant.model.temperature:0.2}") double temperature,
            @Value("${vedal.assistant.model.max-tokens:600}") int maxTokens,
            @Value("${vedal.assistant.model.timeout:PT25S}") Duration timeout) {

        if (!modelAnswers(engine)) {
            log.info("Ведалина отвечает поиском по опубликованному "
                    + "(vedal.assistant.engine={})", engine);
            return search;
        }

        ChatModel model = switch (providerOf(provider)) {
            case YANDEX -> yandexGpt(json, apiKey, modelUri, endpoint, temperature, maxTokens, timeout);
            case GigaChatSettings.PROVIDER -> {
                log.info("Ведалина отвечает моделью GigaChat {}", gigachat.model());
                yield new GigaChatHttp(gigachatAuth.getObject(), gigachat.completionsUrl(), json,
                        gigachat.model(), temperature, maxTokens, timeout);
            }
            case CloudRuSettings.PROVIDER -> {
                log.info("Ведалина отвечает моделью Cloud.ru {}", cloudru.model());
                yield cloudru.chat(json, temperature, maxTokens, timeout);
            }
            default -> throw new IllegalStateException("провайдер " + provider);
        };

        // Векторный поиск подключается перед словесным, а не вместо него:
        // пока корпуса документов нет, индекс пуст, и RagRetrieval честно
        // передаёт слово прежнему поиску. Бина VectorSearch нет вовсе, пока
        // не задан ключ эмбеддингов, — тогда retrieval остаётся прежним.
        Retrieval retrieval = vectors.<Retrieval>stream()
                .findFirst()
                .map(vector -> {
                    log.info("Ведалина ищет по индексу pgvector, "
                            + "не нашлось — поиском по словам");
                    return (Retrieval) new RagRetrieval(vector, new IndexedDocumentSearch(search, jdbc));
                })
                .orElse(search);

        return new ModelEngine(retrieval, model, fallback, documents);
    }

    /** Провайдер Яндекса — значение по умолчанию у обеих настроек. */
    static final String YANDEX = "yandex";

    /**
     * {@code model} — модель отвечает; {@code yandexgpt} — то же самое, старое
     * имя режима, оставлено ради уже развёрнутых окружений. Всё остальное —
     * поиск.
     */
    private static boolean modelAnswers(String engine) {
        if ("yandexgpt".equalsIgnoreCase(engine)) {
            log.info("vedal.assistant.engine=yandexgpt — старое имя режима model; "
                    + "провайдер модели выбирает VEDAL_LLM_PROVIDER");
            return true;
        }
        return "model".equalsIgnoreCase(engine);
    }

    /**
     * Имя провайдера в нижнем регистре — или отказ с перечнем допустимых.
     * Опечатка в переменной должна быть отказом на старте, а не тихим
     * откатом к Яндексу: иначе переключение «не сработало бы» без следа.
     */
    static String providerOf(String provider) {
        var name = provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        if (name.equals(YANDEX) || name.equals(GigaChatSettings.PROVIDER)
                || name.equals(CloudRuSettings.PROVIDER)) {
            return name;
        }
        throw new IllegalStateException(
                "Неизвестный провайдер модели «" + provider + "». VEDAL_LLM_PROVIDER "
                        + "(и VEDAL_RAG_PROVIDER) принимают yandex, gigachat или cloudru.");
    }

    private static ChatModel yandexGpt(ObjectMapper json, String apiKey, String modelUri,
                                       String endpoint, double temperature, int maxTokens,
                                       Duration timeout) {
        // Отказ на старте, а не при первом вопросе посетителя: без ключа
        // модель не ответит ни разу, и узнать об этом лучше при развёртывании,
        // чем из жалобы «ассистент перестал отвечать».
        if (apiKey.isBlank() || modelUri.isBlank()) {
            throw new IllegalStateException("""
                    Провайдер модели yandex, но доступ к ней не задан.
                    Нужны переменные окружения VEDAL_YANDEXGPT_API_KEY (Api-Key \
                    сервисного аккаунта) и VEDAL_YANDEXGPT_MODEL_URI — адрес \
                    модели целиком, вида gpt://<каталог>/yandexgpt-lite/latest. \
                    Без них ассистент отвечать не сможет; чтобы работать без \
                    модели, поставьте vedal.assistant.engine=search, чтобы \
                    отвечать через Сбер — VEDAL_LLM_PROVIDER=gigachat, через \
                    Cloud.ru — VEDAL_LLM_PROVIDER=cloudru.""");
        }

        // Адрес модели проверяется здесь, а не в облаке: без схемы gpt://
        // дверь отвечает 404, и по коду это неотличимо от «нет такой модели».
        // Разбирать URI на каталог и имя портал не станет — его выдаёт консоль
        // целиком, и лишний разбор означал бы третью переменную и третий способ
        // ошибиться.
        if (!modelUri.startsWith("gpt://")) {
            throw new IllegalStateException(
                    "VEDAL_YANDEXGPT_MODEL_URI должен начинаться с gpt:// и выглядеть как "
                            + "gpt://<идентификатор каталога>/yandexgpt-lite/latest, а задано: "
                            + modelUri);
        }

        // Ключ уезжает в заголовок Authorization, а туда пускают только ASCII.
        // Поймано тестом: с кириллицей запрос падает на «invalid header value»,
        // и по этой ошибке не догадаться, что дело в самом ключе, — она
        // приходит из клиента, а не из облака. Обычно это лишний символ,
        // приехавший вместе с копированием из консоли.
        if (!apiKey.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            throw new IllegalStateException(
                    "VEDAL_YANDEXGPT_API_KEY содержит пробелы или не-ASCII символы. "
                            + "Ключ Yandex Cloud состоит из латиницы, цифр и дефисов — "
                            + "похоже, при копировании прихватилось лишнее.");
        }

        log.info("Ведалина отвечает моделью {}", modelUri);
        return new YandexGptHttp(URI.create(endpoint), json, apiKey, modelUri,
                temperature, maxTokens, timeout);
    }

    /**
     * Модель эмбеддингов. Заводится только вместе с ключом.
     *
     * <p>Причина та же, по которой режим ассистента объявляется явно:
     * половинчато настроенный RAG хуже выключенного. Нет ключа — нет бина
     * {@link Embeddings}, нет {@link VectorSearch}, нет {@link KnowledgeIndex},
     * и ассистент работает ровно как до pgvector. Есть ключ — работает всё,
     * и пустой индекс этому не мешает.
     *
     * <p><b>Провайдер эмбеддингов — своя настройка.</b> По умолчанию он тот
     * же, что у генерации ({@code VEDAL_RAG_PROVIDER} наследует
     * {@code VEDAL_LLM_PROVIDER}), но их можно развести: генерацию
     * перевести на GigaChat сегодня, а индекс оставить на Яндексе, пока
     * не найдено окно на миграцию колонки и переиндексацию. Размерность
     * у провайдеров разная, и сверяет её с колонкой {@link #vectorSearch}.
     *
     * <p>Ключ Яндекса тот же, что у YandexGPT: эмбеддинги живут в том же
     * Foundation Models и оплачиваются тем же сервисным аккаунтом. Второй
     * переменной под тот же ключ здесь нет — это был бы второй способ
     * ошибиться. У Сбера так же: один GIGACHAT_AUTH_KEY на всё; у Cloud.ru —
     * один CLOUDRU_API_KEY.
     */
    @Bean
    @ConditionalOnProperty(name = "vedal.assistant.rag.enabled", havingValue = "true")
    Embeddings embeddings(
            ObjectMapper json,
            GigaChatSettings gigachat,
            ObjectProvider<GigaChatAuth> gigachatAuth,
            CloudRuSettings cloudru,
            @Value("${vedal.assistant.rag.provider:${vedal.assistant.provider:" + YANDEX + "}}") String provider,
            @Value("${vedal.assistant.yandex.api-key:}") String apiKey,
            @Value("${vedal.assistant.rag.document-model-uri:}") String documentModelUri,
            @Value("${vedal.assistant.rag.query-model-uri:}") String queryModelUri,
            @Value("${vedal.assistant.rag.endpoint:" + YandexEmbeddings.CLOUD_URL + "}") String endpoint,
            @Value("${vedal.assistant.rag.timeout:PT15S}") Duration timeout) {

        if (providerOf(provider).equals(GigaChatSettings.PROVIDER)) {
            log.info("Индекс Ведалины считается моделью GigaChat {} (размерность {})",
                    gigachat.embeddingsModel(), gigachat.embeddingsDimension());
            return new GigaChatEmbeddings(gigachatAuth.getObject(), gigachat.embeddingsUrl(), json,
                    gigachat.embeddingsModel(), gigachat.embeddingsDimension(), timeout);
        }

        if (providerOf(provider).equals(CloudRuSettings.PROVIDER)) {
            log.info("Индекс Ведалины считается моделью Cloud.ru {} (размерность {})",
                    cloudru.embeddingsModel(),
                    cloudru.embeddingsDimension() == CloudRuEmbeddings.DETECT
                            ? "по первому ответу" : cloudru.embeddingsDimension());
            return cloudru.embeddings(json, timeout);
        }

        if (apiKey.isBlank() || documentModelUri.isBlank() || queryModelUri.isBlank()) {
            throw new IllegalStateException("""
                    vedal.assistant.rag.enabled=true, но доступ к эмбеддингам не задан.
                    Нужны VEDAL_YANDEXGPT_API_KEY и пара адресов моделей поиска: \
                    VEDAL_RAG_DOCUMENT_MODEL_URI (emb://<каталог>/text-search-doc/latest) \
                    и VEDAL_RAG_QUERY_MODEL_URI (emb://<каталог>/text-search-query/latest). \
                    Модели именно две: документы и вопросы кодируются разными, \
                    и векторы попадают в одно пространство ровно поэтому. \
                    Чтобы работать без векторного поиска, поставьте \
                    vedal.assistant.rag.enabled=false.""");
        }

        // Проверка схемы — та же, что у адреса модели генерации, и по той же
        // причине: с чужой схемой дверь отвечает 404, и по коду это
        // неотличимо от «нет такой модели».
        for (var uri : new String[] {documentModelUri, queryModelUri}) {
            if (!uri.startsWith("emb://")) {
                throw new IllegalStateException(
                        "Адрес модели эмбеддингов должен начинаться с emb:// и выглядеть как "
                                + "emb://<идентификатор каталога>/text-search-doc/latest, "
                                + "а задано: " + uri);
            }
        }

        log.info("Индекс Ведалины считается моделью {}", documentModelUri);
        return new YandexEmbeddings(URI.create(endpoint), json, apiKey,
                documentModelUri, queryModelUri, timeout);
    }

    @Bean
    @ConditionalOnProperty(name = "vedal.assistant.rag.enabled", havingValue = "true")
    VectorSearch vectorSearch(JdbcClient jdbc, Embeddings embeddings, PublicDocuments documents,
                              @Value("${vedal.assistant.rag.max-distance:0.45}") double maxDistance) {
        // Колонка и модель обязаны совпасть по размерности — и выясняется это
        // здесь, на старте, а не ошибкой SQL при первой индексации.
        KnowledgeStore.ensureFits(jdbc, embeddings);
        return new VectorSearch(jdbc, embeddings, maxDistance, documents);
    }

    @Bean
    @ConditionalOnProperty(name = "vedal.assistant.rag.enabled", havingValue = "true")
    KnowledgeIndex knowledgeIndex(JdbcClient jdbc, TransactionTemplate transactions,
                                  Embeddings embeddings, CatalogQuery catalog,
                                  ContentQuery content, DocumentQuery documents,
                                  SitePages pages) {
        return new KnowledgeIndex(jdbc, transactions, embeddings, catalog, content, documents,
                pages);
    }

    /**
     * Очередь индексации: правка документа — событие — переиндексация.
     *
     * <p>Условие то же, что у остального RAG. Потребитель без индекса —
     * это событие, которое некому обработать, и отметка «обработано»
     * у необработанного: следующая доставка его уже пропустит.
     */
    @Bean
    @ConditionalOnProperty(name = "vedal.assistant.rag.enabled", havingValue = "true")
    KnowledgeIndexer knowledgeIndexer(KnowledgeIndex index) {
        return new KnowledgeIndexer(index);
    }
}
