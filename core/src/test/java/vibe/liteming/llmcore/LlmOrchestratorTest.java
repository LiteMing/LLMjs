package vibe.liteming.llmcore;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmOrchestratorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void rotatesCredentialsAndStreamsWithoutProviderSwitching() throws Exception {
        List<String> authorizations = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            synchronized (authorizations) {
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"stream\":true")) {
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        ProviderSpec spec = new ProviderSpec("test", "openai",
                "http://localhost:" + server.getAddress().getPort() + "/chat", "model", 0.0, 20,
                List.of(new ProviderSpec.Credential("one", "key-one", 1),
                        new ProviderSpec.Credential("two", "key-two", 1)));
        LlmOrchestrator orchestrator = new LlmOrchestrator(Map.of("test", spec));
        LlmRequest request = new LlmRequest(List.of(new LlmMessage("user", "hi")), List.of("test"),
                0.0, 20, 5, LlmRequestContext.chat());

        assertTrue(orchestrator.send(request).join().success());
        assertTrue(orchestrator.send(request).join().success());
        StringBuilder streamed = new StringBuilder();
        LlmResponse streamResponse = orchestrator.sendStreaming(request, streamed::append).join();

        assertTrue(streamResponse.success());
        assertEquals("Hello", streamed.toString());
        assertEquals(List.of("Bearer key-one", "Bearer key-two", "Bearer key-one"), authorizations);
    }

    @Test
    void rateLimitedCredentialFallsBackToNextSlot() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            if ("Bearer key-one".equals(auth)) {
                body = "{\"error\":{\"message\":\"limited\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
            } else {
                body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ProviderSpec spec = new ProviderSpec("test", "openai",
                "http://localhost:" + server.getAddress().getPort() + "/chat", "model", null, 20,
                List.of(new ProviderSpec.Credential("one", "key-one", 1),
                        new ProviderSpec.Credential("two", "key-two", 1)));
        LlmResponse response = new LlmOrchestrator(Map.of("test", spec)).send(new LlmRequest(
                List.of(new LlmMessage("user", "hi")), List.of("test"), null, 20, 5,
                LlmRequestContext.chat())).join();

        assertTrue(response.success());
        assertEquals("two", response.credentialId());
        assertEquals(2, response.attempts().size());
    }

    @Test
    void badRequestSkipsRemainingCredentialsAndFallsBackProvider() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bad", exchange -> {
            requests.add("bad:" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"error\":{\"message\":\"invalid request\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/good", exchange -> {
            requests.add("good:" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        Map<String, ProviderSpec> specs = new LinkedHashMap<>();
        specs.put("bad", new ProviderSpec("bad", "openai", "http://localhost:" + port + "/bad", "model",
                null, 20, List.of(new ProviderSpec.Credential("bad-one", "key-one", 1),
                        new ProviderSpec.Credential("bad-two", "key-two", 1))));
        specs.put("good", new ProviderSpec("good", "openai", "http://localhost:" + port + "/good", "model",
                null, 20, List.of(new ProviderSpec.Credential("good-one", "key-good", 1))));

        LlmResponse response = new LlmOrchestrator(specs).send(new LlmRequest(
                List.of(new LlmMessage("user", "hi")), List.of("bad", "good"), null, 20, 5,
                LlmRequestContext.chat())).join();

        assertTrue(response.success());
        assertEquals("good", response.provider());
        assertEquals(List.of("bad:Bearer key-one", "good:Bearer key-good"), requests);
    }

    @Test
    void exposesNormalizedFinishReasonForTruncatedResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ProviderSpec spec = new ProviderSpec("test", "openai",
                "http://localhost:" + server.getAddress().getPort() + "/chat", "model", 0.0, 20,
                List.of(new ProviderSpec.Credential("one", "key-one", 1)));
        LlmResponse response = new LlmOrchestrator(Map.of("test", spec)).send(new LlmRequest(
                List.of(new LlmMessage("user", "hi")), List.of("test"), 0.0, 20, 5,
                LlmRequestContext.chat())).join();

        assertTrue(response.success());
        assertEquals("length", response.finishReason());
        assertEquals("length", response.attempts().get(0).finishReason());
    }
}
