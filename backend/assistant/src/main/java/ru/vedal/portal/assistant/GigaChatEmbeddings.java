package ru.vedal.portal.assistant;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Эмбеддинги GigaChat по HTTP: {@code /api/v1/embeddings}.
 *
 * <p><b>Одна модель на документы и вопросы.</b> У Яндекса модели поиска
 * идут парой ({@code text-search-doc} / {@code text-search-query}),
 * у Сбера — одна ({@code Embeddings} или {@code EmbeddingsGigaR}), и ею
 * кодируется и фрагмент, и вопрос. Порт {@link Embeddings} различает
 * документ и вопрос, и здесь оба метода честно ведут в одну модель:
 * различие — в паре у Яндекса, а не в порте.
 *
 * <p><b>Размерность — другая.</b> Векторы GigaChat длиннее яндексовых
 * (ожидаемое значение задаётся настройкой, по умолчанию 1024, у Яндекса
 * 256), а колонка {@code knowledge_chunk.embedding} создана как
 * {@code vector(256)}. Поэтому смена провайдера эмбеддингов — это
 * миграция колонки и полная переиндексация, и портал проверяет это
 * на старте ({@link KnowledgeStore}), а не узнаёт из ошибки SQL.
 *
 * <p>Имя модели в {@link #name()} несёт префикс провайдера: чанки,
 * посчитанные Яндексом, с чанками Сбера несравнимы, и колонка
 * {@code knowledge_chunk.model} обязана их различать.
 */
public class GigaChatEmbeddings implements Embeddings {

    public static final String PATH = "/embeddings";

    /**
     * Длина вектора моделей GigaChat по документации Сбера. Значение
     * по умолчанию для настройки, а не истина: портал сверяет с ним
     * каждый пришедший вектор и колонку в базе.
     */
    public static final int DEFAULT_DIMENSION = 1024;

    private final GigaChatAuth auth;
    private final URI url;
    private final ObjectMapper json;
    private final String model;
    private final int dimension;
    private final Duration timeout;

    public GigaChatEmbeddings(GigaChatAuth auth, URI url, ObjectMapper json, String model,
                              int dimension, Duration timeout) {
        this.auth = auth;
        this.url = url;
        this.json = json;
        this.model = model;
        this.dimension = dimension;
        this.timeout = timeout;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String name() {
        return "gigachat/" + model;
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
        var response = send(body, auth.token());
        if (response.statusCode() == 401) {
            auth.invalidate();
            response = send(body, auth.token());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Эмбеддинги GigaChat ответили " + response.statusCode()
                    + ": " + head(response.body()));
        }
        return parse(response.body());
    }

    private HttpResponse<String> send(String body, String token) {
        var request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return auth.http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание эмбеддинга прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Эмбеддинги недоступны: " + e.getMessage(), e);
        }
    }

    private float[] parse(String body) {
        var vector = OpenAiChat.embedding(json, body);

        // Та же проверка, что у YandexEmbeddings, и по той же причине:
        // вектор другой длины не влезет в колонку, и отказ должен приехать
        // отсюда, с внятным текстом, а не из базы сообщением про SQL.
        if (vector.length != dimension) {
            throw new IllegalStateException(
                    "Модель " + model + " вернула вектор длины " + vector.length
                            + ", а индекс рассчитан на " + dimension + ". Размерность прошита "
                            + "в колонке knowledge_chunk.embedding: смена модели эмбеддингов — это "
                            + "отдельная миграция и полная переиндексация корпуса "
                            + "(GIGACHAT_EMBEDDINGS_DIMENSION и docs/operations/gigachat_activation.md).");
        }
        return vector;
    }

    private static String head(String body) {
        return OpenAiChat.head(body);
    }
}
