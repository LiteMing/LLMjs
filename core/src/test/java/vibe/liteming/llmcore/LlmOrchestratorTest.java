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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmOrchestratorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        LlmRequestAccounting.clear();
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
    void fallbackAttemptsReserveAndSettleAgainstOneCausalRoot() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bad", exchange -> {
            byte[] body = "{\"error\":{\"message\":\"temporary\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/good", exchange -> {
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
                null, 20, List.of(new ProviderSpec.Credential("bad", "key", 1))));
        specs.put("good", new ProviderSpec("good", "openai", "http://localhost:" + port + "/good", "model",
                null, 20, List.of(new ProviderSpec.Credential("good", "key", 1))));
        List<String> roots = new ArrayList<>();
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        List<LlmRequestAccounting.AttemptUsage> settlements =
                java.util.Collections.synchronizedList(new ArrayList<>());
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override
            public LlmRequestAccounting.Reservation reserve(
                    LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                roots.add(request.billingContext().causalRootRequestId());
                return LlmRequestAccounting.Reservation.allow(
                        "reservation-" + ids.incrementAndGet(), estimate.totalTokens());
            }

            @Override
            public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation,
                    LlmRequestAccounting.AttemptUsage usage) {
                settled.incrementAndGet();
                settlements.add(usage);
            }
        });
        LlmBillingContext billing = LlmBillingContext.system(
                LlmBillingContext.PrincipalKind.SCRIPT_SYSTEM, "shared-root", 2, 1_000L);
        LlmRequest request = new LlmRequest(List.of(new LlmMessage("user", "hi")),
                List.of("bad", "good"), null, 20, 5, LlmRequestContext.chat(),
                LlmRouteOptions.empty(), billing);

        LlmResponse response = new LlmOrchestrator(specs).send(request).join();

        assertTrue(response.success());
        assertEquals(List.of("shared-root", "shared-root"), roots);
        assertEquals(2, settled.get());
        assertNull(settlements.get(0));
        assertTrue(settlements.get(1).estimatedTokens() > 0L);
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

    @Test
    void resolvesTestThenPurposeThenProviderDefaultsAndUsesRouteOnWire() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ProviderSpec spec = new ProviderSpec("test", "openai",
                "http://localhost:" + server.getAddress().getPort() + "/chat", "model", 0.2, 100,
                1000, List.of(new ProviderSpec.Credential("one", "key-one", 1)));
        LlmOrchestrator orchestrator = new LlmOrchestrator(Map.of("test", spec));
        orchestrator.setGlobalDefaults(new LlmRouteOptions(null, null, 30, null, null));
        orchestrator.setRoutingConfig(new PriorityRoutingConfig(Map.of("CHAT", List.of("test")), List.of(),
                Map.of("CHAT", new LlmRouteOptions(0.5, 200, 40, 900, 300))));
        LlmRequest routed = LlmRequest.routed(List.of(new LlmMessage("user", "hi")), LlmRequestContext.chat());

        LlmResolvedParameters purpose = orchestrator.resolveParameters(routed, "test");
        assertEquals(0.5, purpose.temperature());
        assertEquals(200, purpose.maxOutputTokens());
        assertEquals(40, purpose.timeoutSeconds());
        assertEquals(700, purpose.inputBudgetTokens());
        assertEquals(300, purpose.outputReserveTokens());

        LlmRequest testOverride = new LlmRequest(routed.messages(), List.of(), null, null, 0, routed.context(),
                new LlmRouteOptions(0.9, 333, 55, 500, 100));
        LlmResolvedParameters test = orchestrator.resolveParameters(testOverride, "test");
        assertEquals(0.9, test.temperature());
        assertEquals(333, test.maxOutputTokens());
        assertEquals(55, test.timeoutSeconds());
        assertEquals(500, test.inputBudgetTokens());

        assertTrue(orchestrator.send(routed).join().success());
        assertTrue(requestBodies.get(0).contains("\"temperature\":0.5"));
        assertTrue(requestBodies.get(0).contains("\"max_tokens\":200"));
    }

    @Test
    void resolvesBoundedOutputWhenNoLayerConfiguresAMaximum() {
        ProviderSpec spec = new ProviderSpec("test", "openai", "http://localhost/unused", "model",
                null, null, 2_000, List.of());
        LlmOrchestrator orchestrator = new LlmOrchestrator(Map.of("test", spec));
        orchestrator.setGlobalDefaults(LlmRouteOptions.empty());

        LlmResolvedParameters resolved = orchestrator.resolveParameters(
                LlmRequest.routed(List.of(), LlmRequestContext.chat()), "test");

        assertEquals(1_000, resolved.maxOutputTokens());
        assertEquals(1_000, resolved.outputReserveTokens());
        assertEquals(1_000, resolved.inputBudgetTokens());
    }

    @Test
    void preservesResponderAndTriggerContextFields() {
        LlmRequestContext context = new LlmRequestContext("req", "NPC_SOCIAL_REALTIME", "entity",
                "minecraft:villager", "Ada", "session", "route", false,
                "entity", "Ada", "NPC:Reimu", "observer", "social");
        assertEquals("entity", context.responderEntityId());
        assertEquals("Ada", context.responderName());
        assertEquals("NPC:Reimu", context.triggerSource());
        assertEquals("observer", context.audience());
        assertEquals("social", context.inputKind());
    }
}
