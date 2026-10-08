package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Эмбеддинги Cloud.ru по HTTP. Устройство то же, что у
 * {@code GigaChatEmbeddingsTest}: сервер свой, наружу тест не ходит.
 * Главное отличие — размерность, которую портал узнаёт по первому ответу.
 */
class CloudRuEmbeddingsTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Дверь эмбеддингов: первому запросу — первый ответ, остальным — последний. */
    private void answering(int status, String... bodies) {
        server.createContext("/v1/embeddings", exchange -> {
            var at = Math.min(calls.getAndIncrement(), bodies.length - 1);
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = bodies[at].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private Embeddings client(int dimension) {
        var settings = new CloudRuSettings("test-cloudru-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                CloudRuSettings.DEFAULT_MODEL, "ai-sage/Giga-Embeddings-instruct-480M", dimension);
        return settings.embeddings(json, Duration.ofSeconds(5));
    }

    /** Ответ двери: вектор нужной длины в схеме OpenAI. */
    private static String vectorOf(int size) {
        return "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":["
                + IntStream.range(0, size).mapToObj(at -> "0.01").collect(Collectors.joining(","))
                + "],\"index\":0}],\"model\":\"ai-sage/Giga-Embeddings-instruct-480M\",\"usage\":{\"total_tokens\":3}}";
    }

    @Test
    void theVectorComesBackUnderTheBearerKeyInOpenAiSchema() {
        answering(200, vectorOf(2048));

        var vector = client(2048).ofDocument("Синтетический фрагмент про красный кубик.");

        assertThat(vector).hasSize(2048);
        assertThat(lastAuth.get()).isEqualTo("Bearer test-cloudru-key");
        assertThat(lastBody.get())
                .contains("\"model\":\"ai-sage/Giga-Embeddings-instruct-480M\"")
                .contains("\"input\":[\"Синтетический фрагмент про красный кубик.\"]");
    }

    // Размерность не задана — она берётся из первого ответа, и дальше
    // с ней сверяется каждый следующий; лишних запросов это не стоит.
    @Test
    void theDimensionIsLearnedFromTheFirstAnswerAndThenEnforced() {
        answering(200, vectorOf(2048), vectorOf(2048), vectorOf(1024));
        var client = client(CloudRuEmbeddings.DETECT);

        assertThat(client.ofDocument("первый фрагмент")).hasSize(2048);
        assertThat(client.dimension()).as("после ответа размерность известна без запроса").isEqualTo(2048);
        assertThat(calls.get()).isEqualTo(1);

        assertThat(client.ofQuery("вопрос")).hasSize(2048);
        assertThatThrownBy(() -> client.ofQuery("ещё вопрос"))
                .hasMessageContaining("1024")
                .hasMessageContaining("2048")
                .hasMessageContaining("CLOUDRU_EMBEDDINGS_DIMENSION");
    }

    // На старте размерность спрашивают до первой индексации (KnowledgeStore):
    // когда она не задана, dimension() сам делает пробный запрос — один раз.
    @Test
    void askingTheDimensionBeforeAnyVectorProbesTheModelOnce() {
        answering(200, vectorOf(2048));
        var client = client(CloudRuEmbeddings.DETECT);

        assertThat(client.dimension()).isEqualTo(2048);
        assertThat(client.dimension()).isEqualTo(2048);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastBody.get()).contains("\"input\":[\"" + CloudRuEmbeddings.PROBE + "\"]");
    }

    // Заданная размерность — обещание, и ответ другой длины его нарушает:
    // отказ приезжает отсюда, с внятным текстом, а не из базы.
    @Test
    void aDeclaredDimensionIsEnforcedWithoutAProbe() {
        answering(200, vectorOf(1024));
        var client = client(2048);

        assertThat(client.dimension()).isEqualTo(2048);
        assertThat(calls.get()).as("заданную размерность узнавать не надо").isZero();

        assertThatThrownBy(() -> client.ofDocument("Синтетический фрагмент."))
                .hasMessageContaining("1024")
                .hasMessageContaining("2048")
                .hasMessageContaining("переиндексация")
                .hasMessageContaining("cloudru_models_activation.md");
    }

    // Модель одна на документы и вопросы; имя несёт провайдера, чтобы чанки
    // разных дверей в одной таблице не смешивались.
    @Test
    void oneModelServesDocumentsAndQuestionsAndIsNamedWithTheProvider() {
        answering(200, vectorOf(16));
        var client = client(16);
        var bodies = new ArrayList<String>();

        client.ofDocument("Синтетический фрагмент.");
        bodies.add(lastBody.get());
        client.ofQuery("что это за фигура");
        bodies.add(lastBody.get());

        assertThat(bodies).allSatisfy(body ->
                assertThat(body).contains("\"model\":\"ai-sage/Giga-Embeddings-instruct-480M\""));
        assertThat(client.name()).isEqualTo("cloudru/ai-sage/Giga-Embeddings-instruct-480M");
    }

    @Test
    void anErrorFromTheDoorBecomesAFailureAndNotAnEmptyVector() {
        answering(429, "{\"error\":{\"message\":\"Too Many Requests\"}}");

        assertThatThrownBy(() -> client(16).ofQuery("вопрос"))
                .hasMessageContaining("429")
                .hasMessageContaining("Cloud.ru");
    }

    @Test
    void aRefusedKeyNamesTheVariable() {
        answering(401, "{\"error\":{\"message\":\"Unauthorized\"}}");

        assertThatThrownBy(() -> client(16).ofQuery("вопрос"))
                .hasMessageContaining("401")
                .hasMessageContaining("CLOUDRU_API_KEY");
    }

    @Test
    void anAnswerWithoutAVectorIsAFailure() {
        answering(200, "{\"object\":\"list\",\"data\":[]}");

        assertThatThrownBy(() -> client(16).ofQuery("вопрос"))
                .hasMessageContaining("без вектора");
    }

    @Test
    void aNegativeDimensionSettingIsRefusedOnStartup() {
        assertThatThrownBy(() -> new CloudRuSettings("key", "", "m", "e", -1)
                .embeddings(json, Duration.ofSeconds(5)))
                .hasMessageContaining("CLOUDRU_EMBEDDINGS_DIMENSION");
        assertThatThrownBy(() -> new CloudRuSettings("key", "", "m", " ", 0)
                .embeddings(json, Duration.ofSeconds(5)))
                .hasMessageContaining("CLOUDRU_EMBEDDINGS_MODEL");
    }
}
