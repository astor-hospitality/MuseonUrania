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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разговор с GigaChat по HTTP.
 *
 * <p>Сервер поднимается тут же, на случайном порту, и играет обе двери —
 * OAuth и генерацию: наружу тест не ходит. Проверяется то, что можно
 * проверить только на настоящем обмене: как разбирается поток SSE, что
 * уходит в заголовках и теле, и что происходит при отказе.
 */
class GigaChatHttpTest {

    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final AtomicInteger completions = new AtomicInteger();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/oauth", exchange -> {
            var body = ("{\"access_token\":\"token-" + tokensIssued.incrementAndGet()
                    + "\",\"expires_at\":" + (System.currentTimeMillis() + 1_800_000) + "}")
                    .getBytes(StandardCharsets.UTF_8);
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

    /** Дверь генерации: первому запросу — первый статус, остальным — последний. */
    private void answering(int[] statuses, String... lines) {
        server.createContext("/api/v1/chat/completions", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            var at = Math.min(completions.getAndIncrement(), statuses.length - 1);
            var status = statuses[at];
            var body = (status == 200
                    ? String.join("\n", lines)
                    : "{\"status\":" + status + ",\"message\":\"Unauthorized\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private void answering(int status, String... lines) {
        answering(new int[] {status}, lines);
    }

    private ChatModel client() {
        var root = "http://127.0.0.1:" + server.getAddress().getPort();
        var auth = new GigaChatAuth(HttpClient.newHttpClient(), URI.create(root + "/api/v2/oauth"),
                json, "dGVzdDpzZWNyZXQ=", "GIGACHAT_API_PERS", Duration.ofSeconds(5));
        return new GigaChatHttp(auth, URI.create(root + "/api/v1/chat/completions"), json,
                "GigaChat", 0.2, 600, Duration.ofSeconds(5));
    }

    /** Событие потока: приращение, как его отдаёт дверь. */
    private static String delta(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text
                + "\",\"role\":\"assistant\"},\"index\":0}],\"object\":\"chat.completion.chunk\"}";
    }

    // В отличие от Яндекса, строка несёт ПРИРАЩЕНИЕ: куски складываются,
    // а не вычитаются, и склейка обязана совпасть с записанным ответом.
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

    // Ключ в дверь генерации не уходит — только токен, полученный за него.
    @Test
    void theRequestCarriesTheBearerTokenNotTheKey() {
        answering(200, delta("ответ"), "data: [DONE]");

        client().complete(List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(lastAuth.get()).isEqualTo("Bearer token-1");
    }

    // Схема запроса OpenAI-подобная: model, messages с content, max_tokens.
    @Test
    void theRequestIsShapedForGigaChat() {
        answering(200, delta("ответ"), "data: [DONE]");

        client().complete(List.of(
                new ChatModel.Message(ChatModel.Role.SYSTEM, "правила"),
                new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(lastBody.get())
                .contains("\"model\":\"GigaChat\"")
                .contains("\"stream\":true")
                .contains("\"max_tokens\":600")
                .contains("\"temperature\":0.2")
                .contains("\"role\":\"system\",\"content\":\"правила\"")
                .contains("\"role\":\"user\",\"content\":\"вопрос\"");
        assertThat(lastBody.get()).as("поле Яндекса, у Сбера его нет").doesNotContain("\"text\"");
    }

    // Отозванный токен: один повтор с новым — и ответ, а не отказ.
    @Test
    void anExpiredTokenIsRenewedOnceAndTheRequestRepeated() {
        answering(new int[] {401, 200}, delta("Ответ."), "data: [DONE]");

        var full = client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { });

        assertThat(full).isEqualTo("Ответ.");
        assertThat(lastAuth.get()).isEqualTo("Bearer token-2");
        assertThat(completions.get()).isEqualTo(2);
    }

    // Второй 401 подряд — дело в ключе, а не в сроке: повторов больше нет.
    @Test
    void aSecondRefusalIsAnErrorNotAnEndlessRetry() {
        answering(new int[] {401, 401});

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("401")
                .hasMessageContaining("Unauthorized");
        assertThat(completions.get()).isEqualTo(2);
    }

    // Строка незнакомого вида — повод её пропустить, а не оборвать ответ.
    @Test
    void unknownLinesAreSkippedRatherThanBreakingTheAnswer() {
        answering(200, ": keep-alive", "data: {\"choices\":[]}", "не json вовсе",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}",
                delta("Ответ."), "data: [DONE]");

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
        answering(500);

        assertThatThrownBy(() -> client().complete(
                List.of(new ChatModel.Message(ChatModel.Role.USER, "вопрос")), c -> { }))
                .hasMessageContaining("500");
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
}
