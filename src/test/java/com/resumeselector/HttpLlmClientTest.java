package com.resumeselector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hr.llm.HttpLlmClient;
import com.hr.llm.LlmException;
import com.hr.llm.LlmProvider;
import com.hr.llm.LlmSettings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Teste le client contre un faux serveur HTTP local (aucun appel réseau externe). */
class HttpLlmClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String route, int status, String json) {
        server.createContext(route, exchange -> {
            path.set(exchange.getRequestURI().getPath());
            headers.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
    }

    private HttpLlmClient client(LlmProvider provider, String model, String key) {
        return new HttpLlmClient(new LlmSettings(provider, baseUrl, model, key),
                Duration.ofSeconds(5), 3, Duration.ofMillis(10));
    }

    @Test
    void anthropicRequestAndResponse() throws Exception {
        respond("/", 200, "{\"content\":[{\"type\":\"text\",\"text\":\"Bon\"},{\"type\":\"text\",\"text\":\"jour\"}]}");

        String reply = client(LlmProvider.ANTHROPIC, "claude-test", "sk-ant-123").complete("SYS", "USER");

        assertEquals("Bonjour", reply);
        assertEquals("/v1/messages", path.get());
        assertEquals("sk-ant-123", headers.get().getFirst("x-api-key"));
        assertEquals("2023-06-01", headers.get().getFirst("anthropic-version"));
        JsonObject sent = JsonParser.parseString(body.get()).getAsJsonObject();
        assertEquals("claude-test", sent.get("model").getAsString());
        assertEquals("SYS", sent.get("system").getAsString());
        assertTrue(sent.has("max_tokens"));
        assertEquals(1, sent.getAsJsonArray("messages").size());
        assertEquals("USER", sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void anthropicBaseUrlEndingWithV1IsNotDuplicated() throws Exception {
        respond("/", 200, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        HttpLlmClient c = new HttpLlmClient(new LlmSettings(LlmProvider.ANTHROPIC, baseUrl + "/v1/", "m", "k"),
                Duration.ofSeconds(5), 1, Duration.ZERO);

        c.complete("s", "u");

        assertEquals("/v1/messages", path.get());
    }

    @Test
    void openAiCompatibleRequestAndResponse() throws Exception {
        respond("/", 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}");

        String reply = client(LlmProvider.OPENAI, "gpt-test", "sk-openai").complete("SYS", "USER");

        assertEquals("OK", reply);
        assertEquals("/chat/completions", path.get());
        assertEquals("Bearer sk-openai", headers.get().getFirst("Authorization"));
        JsonObject sent = JsonParser.parseString(body.get()).getAsJsonObject();
        assertEquals("gpt-test", sent.get("model").getAsString());
        assertEquals("system", sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("user", sent.getAsJsonArray("messages").get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void localProviderWithoutKeySendsNoAuthorizationHeader() throws Exception {
        respond("/", 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");

        client(LlmProvider.OLLAMA, "llama3.1", "").complete("s", "u");

        assertNull(headers.get().getFirst("Authorization"));
    }

    @Test
    void rejectedKeyIsFatalAndNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] out = "{\"error\":{\"message\":\"invalid x-api-key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        LlmException e = assertThrows(LlmException.class,
                () -> client(LlmProvider.ANTHROPIC, "m", "bad").complete("s", "u"));

        assertTrue(e.isFatal());
        assertTrue(e.getMessage().contains("invalid x-api-key"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void rateLimitIsRetriedThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            boolean first = calls.incrementAndGet() == 1;
            byte[] out = (first ? "{\"error\":{\"message\":\"slow down\"}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first ? 429 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertEquals("OK", client(LlmProvider.OPENAI, "m", "k").complete("s", "u"));
        assertEquals(2, calls.get());
    }

    @Test
    void persistentServerErrorIsNonFatalAfterRetries() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] out = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        LlmException e = assertThrows(LlmException.class,
                () -> client(LlmProvider.OPENAI, "m", "k").complete("s", "u"));

        assertFalse(e.isFatal());
        assertEquals(3, calls.get());
    }

    @Test
    void unreachableServerIsFatal() throws Exception {
        int freePort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            freePort = socket.getLocalPort();
        } // port libéré : plus personne n'écoute dessus
        HttpLlmClient c = new HttpLlmClient(
                new LlmSettings(LlmProvider.OLLAMA, "http://127.0.0.1:" + freePort + "/v1", "m", ""),
                Duration.ofSeconds(5), 3, Duration.ofMillis(10));

        LlmException e = assertThrows(LlmException.class, () -> c.complete("s", "u"));

        assertTrue(e.isFatal());
    }

    @Test
    void emptyAnswerIsAnError() {
        respond("/", 200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}");

        LlmException e = assertThrows(LlmException.class,
                () -> client(LlmProvider.OPENAI, "m", "k").complete("s", "u"));

        assertFalse(e.isFatal());
    }
}
