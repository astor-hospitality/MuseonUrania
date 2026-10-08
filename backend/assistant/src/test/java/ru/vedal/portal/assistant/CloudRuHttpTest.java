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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разговор с Cloud.ru Foundation Models по HTTP.
 *
 * <p>Сервер поднимается тут же, на случайном порту, и играет дверь
 * {@code /v1/chat/completions}: наружу тест не ходит. Проверяется то, что
 * можно проверить только на настоящем обмене: как разбирается поток SSE,
 * что уходит в заголовках и теле, и что происходит при отказе. OAuth-двери
 * здесь нет: ключ статический и уходит в запрос как есть.
 */
class CloudRuHttpTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger completions = new AtomicInteger();
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

    private void answering(int status, String... lines) {
        server.createContext("/v1/chat/completions", exchange -> {
            completions.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            var body = (status == 200
                    ? String.join("\n", lines)
                    : "{\"error\":{\"message\":\"Unauthorized\",\"code\":" + status + "}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private ChatModel client() {
        var settings = new CloudRuSettings("test-cloudru-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                "GigaChat/GigaChat-2-Max", CloudRuSettings.DEFAULT_EMBEDDINGS_MODEL, CloudRuEmbeddings.DETECT);
        return settings.chat(json, 0.2, 600, Duration.ofSeconds(5));
    }

    /** Событие потока: приращение, как его отдаёт дверь. */
    private static String delta(String text) {
        return "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"" + text + "\",\"role\":\"assistant\"},\"finish_reason\":null}]}";
    }

    // Строка несёт ПРИРАЩЕНИЕ: куски складываются, и склейка обязана
    // совпасть с записанным ответом.
    @Test
    void chunksAreDeltasAndTheirJoinIsTheAnswer() {
        answering(200, delta("Инкубатор"), "", delta(" VEDAL"), "", delta(" A-2000."), "",
                "data: [DONE]");

        var chunks = new ArrayList<String>();
        var full = client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "что это")), chunks::add);

        assertThat(full).isEqualTo("Инкубатор VEDAL A-2000.");
        assertThat(chunks).containsExactly("Инкубатор", " VEDAL", " A-2000.");
        assertThat(String.join("", chunks))
                .as("Склейка кусков обязана совпасть с записанным ответом")
                .isEqualTo(full);
    }

    // Ключ статический и уходит как Bearer — обмена на токен, как у Сбера, нет.
    @Test
    void theRequestCarriesTheApiKeyAsBearer() {
        answering(200, delta("ответ"), "data: [DONE]");

        client().complete(List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(lastAuth.get()).isEqualTo("Bearer test-cloudru-key");
    }

    // Схема запроса OpenAI: model с семейством, messages с content, max_tokens;
    // адрес собирается из корня без двойной косой черты.
    @Test
    void theRequestIsShapedForOpenAiSchema() {
        answering(200, delta("ответ"), "data: [DONE]");

        client().complete(List.of(
                new ChatModel.Message(ChatModel.Role.SYSTEM, "правила"),
                new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(lastBody.get())
                .contains("\"model\":\"GigaChat/GigaChat-2-Max\"")
                .contains("\"stream\":true")
                .contains("\"max_tokens\":600")
                .contains("\"temperature\":0.2")
                .contains("\"role\":\"system\",\"content\":\"правила\"")
                .contains("\"role\":\"user\",\"content\":\"вопрос\"");
        assertThat(lastBody.get()).as("поле Яндекса, в схеме OpenAI его нет").doesNotContain("\"text\"");
    }

    @Test
    void theAddressIsBuiltFromTheBaseUrl() {
        var settings = new CloudRuSettings("k", "https://foundation-models.api.cloud.ru/v1/",
                "m", "e", 0);

        assertThat(settings.completionsUrl())
                .isEqualTo(URI.create("https://foundation-models.api.cloud.ru/v1/chat/completions"));
        assertThat(settings.embeddingsUrl())
                .isEqualTo(URI.create("https://foundation-models.api.cloud.ru/v1/embeddings"));
        assertThat(new CloudRuSettings("k", "", "m", "e", 0).completionsUrl())
                .as("пустой адрес — корень облака")
                .isEqualTo(URI.create(CloudRuSettings.CLOUD_API + "/chat/completions"));
    }

    // 401 со статическим ключом — дело в ключе, повторять нечем: отказ сразу,
    // с подсказкой, какую переменную проверить.
    @Test
    void aRefusedKeyIsAnErrorWithoutARetry() {
        answering(401);

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("401")
                .hasMessageContaining("Unauthorized")
                .hasMessageContaining("CLOUDRU_API_KEY");
        assertThat(completions.get()).isEqualTo(1);
    }

    // Строка незнакомого вида — повод её пропустить, а не оборвать ответ.
    @Test
    void unknownLinesAreSkippedRatherThanBreakingTheAnswer() {
        answering(200, ": keep-alive", "data: {\"choices\":[]}", "не json вовсе",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}",
                delta("Ответ."),
                "data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":7}}",
                "data: [DONE]");

        var full = client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(full).isEqualTo("Ответ.");
    }

    // Дверь ответила не потоком, а целиком: текст всё равно достаётся.
    @Test
    void aWholeAnswerInsteadOfAStreamIsStillAnAnswer() {
        answering(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Целиком.\"},"
                + "\"index\":0,\"finish_reason\":\"stop\"}]}");

        var full = client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(full).isEqualTo("Целиком.");
    }

    @Test
    void aRefusalIsAnErrorAndCarriesTheReason() {
        answering(503);

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("503")
                .hasMessageContaining("Cloud.ru");
    }

    // Пустой ответ — тоже отказ: показать посетителю пустой пузырь хуже,
    // чем отдать перечень найденного.
    @Test
    void anEmptyAnswerIsTreatedAsAFailure() {
        answering(200, "data: [DONE]");

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("пустой ответ");
    }

    // Ключ проверяется до первого запроса — на старте, а не от посетителя.
    @Test
    void aBlankOrStrayKeyIsRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> new CloudRuSettings("", "", "m", "e", 0).chat(json, 0.2, 600, Duration.ofSeconds(5)))
                .hasMessageContaining("CLOUDRU_API_KEY")
                .hasMessageContaining("console.cloud.ru");
        assertThatThrownBy(() -> new CloudRuSettings("ключ", "", "m", "e", 0).chat(json, 0.2, 600, Duration.ofSeconds(5)))
                .hasMessageContaining("не-ASCII");
        assertThatThrownBy(() -> new CloudRuSettings("key", "", " ", "e", 0).chat(json, 0.2, 600, Duration.ofSeconds(5)))
                .hasMessageContaining("CLOUDRU_MODEL");
    }
}
