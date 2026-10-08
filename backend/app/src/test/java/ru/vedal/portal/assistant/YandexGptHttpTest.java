package ru.vedal.portal.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разговор с моделью по HTTP.
 *
 * <p>Сервер поднимается тут же, на случайном порту: наружу тест не ходит —
 * ни за деньги, ни за чужой доступностью. Проверяется то, что можно проверить
 * только на настоящем обмене: как разбирается поток, что уходит в заголовках
 * и что происходит при отказе.
 *
 * <p>Адрес двери приходит конструктором — потому тест и говорит со своим
 * сервером тем же кодом, что работает в проде. Настройкой приложения адрес
 * при этом не стал: в работе он один, и меняться ему незачем.
 */
class YandexGptHttpTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
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

    /** Дверь, отвечающая заданными строками потока. */
    private void answering(int status, String... lines) {
        server.createContext("/foundationModels/v1/completion", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            var body = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** Клиент, говорящий с поднятым здесь сервером. */
    private ChatModel client() {
        var here = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/foundationModels/v1/completion");
        return new YandexGptHttp(here, json, "test-api-key-ascii",
                "gpt://каталог-1/yandexgpt-lite/latest", 0.2, 600, Duration.ofSeconds(5));
    }

    /** Строка потока: ответ целиком на текущий момент, как его отдаёт облако. */
    private static String line(String text) {
        return "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\""
                + text + "\"},\"status\":\"ALTERNATIVE_STATUS_PARTIAL\"}]}}";
    }

    // Каждая строка несёт ответ ЦЕЛИКОМ, а не приращение. Отдай её как есть —
    // и посетитель увидит ответ, растущий копиями самого себя.
    @Test
    void chunksAreDifferencesNotWholeAnswersOverAndOver() {
        answering(200, line("Инкубатор"), line("Инкубатор VEDAL"), line("Инкубатор VEDAL A-2000."));

        var chunks = new ArrayList<String>();
        var full = client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "что это")), chunks::add);

        assertThat(full).isEqualTo("Инкубатор VEDAL A-2000.");
        assertThat(chunks).containsExactly("Инкубатор", " VEDAL", " A-2000.");
        assertThat(String.join("", chunks))
                .as("Склейка кусков обязана совпасть с записанным ответом")
                .isEqualTo(full);
    }

    // Api-Key, а не Bearer: у статического ключа своя схема, и с Bearer дверь
    // отвечает 401 — по тексту ошибки это выглядит как «ключ неверный».
    @Test
    void theKeyGoesWithItsOwnScheme() {
        answering(200, line("ответ"));

        client().complete(List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(lastAuth.get()).isEqualTo("Api-Key test-api-key-ascii");
    }

    // Модель адресуется вместе с каталогом: он определяет, чей счёт платит.
    @Test
    void theRequestNamesTheModelAndTheFolder() {
        answering(200, line("ответ"));

        client().complete(List.of(
                new ChatModel.Message(ChatModel.Role.SYSTEM, "правила"),
                new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(lastBody.get())
                .contains("gpt://каталог-1/yandexgpt-lite/latest")
                .contains("\"stream\":true")
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"");
    }

    // Строка незнакомого вида — повод её пропустить, а не оборвать ответ:
    // дверь присылает и служебные объекты без текста.
    @Test
    void unknownLinesAreSkippedRatherThanBreakingTheAnswer() {
        answering(200, "{\"result\":{}}", "не json вовсе", line("Ответ."));

        var full = client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(full).isEqualTo("Ответ.");
    }

    // Отказ двери — исключение, а не пустой ответ: выше по стеку оно значит
    // «отдай перечень найденного», и перепутать его с ответом нельзя.
    @Test
    void aRefusalIsAnErrorAndCarriesTheReason() {
        answering(401, "{\"error\":\"unauthorized\"}");

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("401")
                .hasMessageContaining("unauthorized");
    }

    // Пустой ответ — тоже отказ: показать посетителю пустой пузырь хуже,
    // чем отдать перечень найденного.
    @Test
    void anEmptyAnswerIsTreatedAsAFailure() {
        answering(200, "{\"result\":{\"alternatives\":[]}}");

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("пустой ответ");
    }
}
