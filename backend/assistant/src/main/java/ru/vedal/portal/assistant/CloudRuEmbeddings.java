package ru.vedal.portal.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Эмбеддинги Cloud.ru Foundation Models по HTTP: {@code /embeddings}.
 *
 * <p><b>Одна модель на документы и вопросы</b> — как у Сбера
 * ({@link GigaChatEmbeddings}): пары, как у Яндекса, здесь нет, и оба
 * метода порта честно ведут в одну модель. Схема OpenAI: {@code {model,
 * input}} → {@code data[0].embedding}; разбор общий, {@link OpenAiChat}.
 *
 * <p><b>Размерность узнаётся, а не постулируется.</b> Длина вектора
 * {@code Giga-Embeddings-instruct} в документации Cloud.ru не названа,
 * и прошивать число наугад значило бы заведомо ронять старт. Поэтому
 * правило такое: задана {@code CLOUDRU_EMBEDDINGS_DIMENSION} — ею
 * сверяется каждый ответ; не задана ({@code 0}) — размерность берётся
 * из первого ответа модели и дальше сверяется с ним. {@link #dimension()}
 * спрашивают на старте ({@link KnowledgeStore#ensureFits}), и в этом
 * случае он сам делает пробный запрос: сверка колонки с моделью
 * по-прежнему идёт до первой индексации, а не после ошибки SQL.
 *
 * <p>Имя модели в {@link #name()} несёт префикс провайдера: чанки,
 * посчитанные разными дверями, несравнимы, и колонка
 * {@code knowledge_chunk.model} обязана их различать.
 */
public class CloudRuEmbeddings implements Embeddings {

    private static final Logger log = LoggerFactory.getLogger(CloudRuEmbeddings.class);

    public static final String PATH = "/embeddings";

    /** Значение настройки «узнать размерность по первому ответу». */
    public static final int DETECT = 0;

    /** Текст пробного запроса, которым узнаётся размерность. */
    static final String PROBE = "размерность";

    private final URI url;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    /** Длина вектора; {@link #DETECT}, пока не пришёл первый ответ. */
    private volatile int dimension;

    public CloudRuEmbeddings(URI url, ObjectMapper json, String apiKey, String model,
                             int dimension, Duration timeout) {
        this.url = url;
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = Math.max(dimension, DETECT);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public int dimension() {
        if (dimension == DETECT) {
            synchronized (this) {
                if (dimension == DETECT) vector(PROBE);
            }
        }
        return dimension;
    }

    @Override
    public String name() {
        return "cloudru/" + model;
    }

    @Override
    public float[] ofDocument(String text) {
        return vector(text);
    }

    @Override
    public float[] ofQuery(String text) {
        return vector(text);
    }

    private float[] vector(String text) {
        var body = json.writeValueAsString(Map.of("model", model, "input", List.of(text)));
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание эмбеддинга прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Эмбеддинги недоступны: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Эмбеддинги Cloud.ru ответили " + response.statusCode()
                    + ": " + OpenAiChat.head(response.body())
                    + (response.statusCode() == 401 ? " (проверьте CLOUDRU_API_KEY)" : ""));
        }
        return fitted(OpenAiChat.embedding(json, response.body()));
    }

    /**
     * Сверить длину с известной — или запомнить её, если это первый ответ.
     *
     * <p>Та же проверка, что у {@link YandexEmbeddings}, и по той же
     * причине: вектор другой длины не влезет в колонку, и отказ должен
     * приехать отсюда, с внятным текстом, а не из базы сообщением про SQL.
     */
    private synchronized float[] fitted(float[] vector) {
        if (dimension == DETECT) {
            dimension = vector.length;
            log.info("Размерность эмбеддингов {} узнана по первому ответу: {}", name(), dimension);
            return vector;
        }
        if (vector.length != dimension) {
            throw new IllegalStateException(
                    "Модель " + model + " вернула вектор длины " + vector.length
                            + ", а индекс рассчитан на " + dimension + ". Размерность прошита "
                            + "в колонке knowledge_chunk.embedding: смена модели эмбеддингов — это "
                            + "отдельная миграция и полная переиндексация корпуса "
                            + "(CLOUDRU_EMBEDDINGS_DIMENSION и docs/operations/cloudru_models_activation.md).");
        }
        return vector;
    }
}
