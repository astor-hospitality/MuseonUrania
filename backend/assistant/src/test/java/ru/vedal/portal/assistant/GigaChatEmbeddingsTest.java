package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Эмбеддинги GigaChat по HTTP. Устройство то же, что у
 * {@code YandexEmbeddingsTest}, и по тем же причинам: сервер свой, наружу
 * тест не ходит.
 */
class GigaChatEmbeddingsTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/oauth", exchange -> {
            var body = ("{\"access_token\":\"token-1\",\"expires_at\":"
                    + (System.currentTimeMillis() + 1_800_000) + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answering(int status, String body) {
        server.createContext("/api/v1/embeddings", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private Embeddings client() {
        var root = "http://127.0.0.1:" + server.getAddress().getPort();
        var auth = new GigaChatAuth(HttpClient.newHttpClient(), URI.create(root + "/api/v2/oauth"),
                json, "dGVzdDpzZWNyZXQ=", "GIGACHAT_API_PERS", Duration.ofSeconds(5));
        return new GigaChatEmbeddings(auth, URI.create(root + "/api/v1/embeddings"), json,
                "Embeddings", 1024, Duration.ofSeconds(5));
    }

    /** Ответ двери: вектор нужной длины в схеме OpenAI. */
    private static String vectorOf(int size) {
        return "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":["
                + IntStream.range(0, size).mapToObj(at -> "0.01").collect(Collectors.joining(","))
                + "],\"index\":0}],\"model\":\"Embeddings\"}";
    }

    @Test
    void theVectorComesBackWithTheDeclaredDimensionUnderTheBearerToken() {
        answering(200, vectorOf(1024));

        var vector = client().ofDocument("Синтетический фрагмент про красный кубик.");

        assertThat(vector).hasSize(1024);
        assertThat(lastAuth.get()).isEqualTo("Bearer token-1");
        assertThat(lastBody.get())
                .contains("\"model\":\"Embeddings\"")
                .contains("\"input\":[\"Синтетический фрагмент про красный кубик.\"]");
    }

    // У Сбера модель одна на документы и вопросы; имя несёт провайдера,
    // чтобы чанки Яндекса и Сбера в одной таблице не смешивались.
    @Test
    void oneModelServesDocumentsAndQuestionsAndIsNamedWithTheProvider() {
        answering(200, vectorOf(1024));
        var client = client();

        client.ofDocument("Синтетический фрагмент.");
        var forDocument = lastBody.get();
        client.ofQuery("что это за фигура");
        var forQuery = lastBody.get();

        assertThat(forDocument).contains("\"model\":\"Embeddings\"");
        assertThat(forQuery).contains("\"model\":\"Embeddings\"");
        assertThat(client.name()).isEqualTo("gigachat/Embeddings");
    }

    // Размерность прошита в колонке, и вектор другой длины туда не влезет:
    // отказ приезжает отсюда, с внятным текстом, а не из базы.
    @Test
    void aVectorOfAnotherLengthIsRefusedWithAReadableMessage() {
        answering(200, vectorOf(256));

        assertThatThrownBy(() -> client().ofDocument("Синтетический фрагмент."))
                .hasMessageContaining("256")
                .hasMessageContaining("1024")
                .hasMessageContaining("переиндексация");
    }

    @Test
    void anErrorFromTheDoorBecomesAFailureAndNotAnEmptyVector() {
        answering(429, "{\"status\":429,\"message\":\"Too Many Requests\"}");

        assertThatThrownBy(() -> client().ofQuery("вопрос"))
                .hasMessageContaining("429");
    }

    @Test
    void anAnswerWithoutAVectorIsAFailure() {
        answering(200, "{\"object\":\"list\",\"data\":[]}");

        assertThatThrownBy(() -> client().ofQuery("вопрос"))
                .hasMessageContaining("без вектора");
    }
}
