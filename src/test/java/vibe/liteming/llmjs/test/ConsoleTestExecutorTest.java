package vibe.liteming.llmjs.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRouteOptions;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.ProviderSpec;
import vibe.liteming.llmcore.PurposeMeta;
import vibe.liteming.llmcore.PurposeRegistry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTestExecutorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void executesPurposeFallbackAndReturnsFinalRequestTrace() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bad", exchange -> {
            byte[] body = "{\"error\":{\"message\":\"temporary\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/good", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":3}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        Map<String, ProviderSpec> providers = new LinkedHashMap<>();
        providers.put("bad", provider("bad", port, "/bad"));
        providers.put("good", provider("good", port, "/good"));
        LlmOrchestrator orchestrator = new LlmOrchestrator(providers);
        String purpose = "CONSOLE_PIPELINE_TEST";
        PurposeRegistry.register(new PurposeMeta(purpose, purpose, "", "test", false));
        PriorityRoutingConfig routing = new PriorityRoutingConfig(
                Map.of(purpose, List.of("bad", "good")), List.of(),
                Map.of(purpose, new LlmRouteOptions(0.4, 64, 30, 70, 20)));
        orchestrator.setRoutingConfig(routing);
        ConsoleTestRequest request = new ConsoleTestRequest(ConsoleTestRequest.SCHEMA_VERSION,
                UUID.randomUUID().toString(), ConsoleTestRequest.RoutingMode.PURPOSE, purpose, "DIRECT_CHAT",
                List.of(), List.of(
                        ConsoleTestRequest.MessageEntry.text("required", "test", "system", "required", true, 1000),
                        ConsoleTestRequest.MessageEntry.text("low", "test", "user",
                                "this optional entry is deliberately too long for the budget", false, 1),
                        ConsoleTestRequest.MessageEntry.text("high", "test", "user", "high", false, 100)),
                LlmRouteOptions.empty(), Map.of("responderName", "Reimu"));

        String resultJson = ConsoleTestExecutor.execute(orchestrator, routing, request).join();
        JsonObject result = JsonParser.parseString(resultJson).getAsJsonObject();

        assertTrue(result.get("success").getAsBoolean());
        assertEquals("good", result.get("provider").getAsString());
        assertEquals("stop", result.get("finishReason").getAsString());
        assertEquals(3, result.get("reasoningTokens").getAsInt());
        assertEquals(2, result.getAsJsonArray("attempts").size());
        assertEquals(0.4, result.getAsJsonObject("effective").get("temperature").getAsDouble());
        assertEquals(2, result.getAsJsonArray("finalMessages").size());
        assertFalse(result.getAsJsonArray("budgetDecisions").get(1).getAsJsonObject()
                .get("included").getAsBoolean());
        assertTrue(result.get("requestBody").getAsString().contains("required"));
        assertTrue(result.get("responseBody").getAsString().contains("reasoning_tokens"));

        ConsoleTestRequest explicit = new ConsoleTestRequest(ConsoleTestRequest.SCHEMA_VERSION,
                UUID.randomUUID().toString(), ConsoleTestRequest.RoutingMode.EXPLICIT_CHAIN, purpose, "DIRECT_CHAT",
                List.of("good"), request.messages(), new LlmRouteOptions(0.9, null, null, null, null), Map.of());
        JsonObject explicitResult = JsonParser.parseString(
                ConsoleTestExecutor.execute(orchestrator, routing, explicit).join()).getAsJsonObject();
        assertEquals(1, explicitResult.getAsJsonArray("attempts").size());
        assertEquals("good", explicitResult.get("provider").getAsString());
        assertEquals(0.9, explicitResult.getAsJsonObject("effective").get("temperature").getAsDouble());
    }

    @Test
    void codecRejectsUnknownModeAndUnregisteredPurpose() {
        String requestId = UUID.randomUUID().toString();
        String unknownMode = "{\"schemaVersion\":1,\"requestId\":\"" + requestId
                + "\",\"routingMode\":\"SIDEWAYS\",\"purpose\":\"CHAT\",\"messages\":[]}";
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ConsoleTestCodec.parseRequest(unknownMode, false)).getMessage().contains("routingMode"));

        ConsoleTestRequest request = ConsoleTestRequest.simple(UUID.randomUUID(), "hello", "");
        String json = ConsoleTestCodec.toJson(new ConsoleTestRequest(request.schemaVersion(), request.requestId(),
                request.routingMode(), "NOT_REGISTERED_FOR_TEST", request.generationType(), request.providerChain(),
                request.messages(), request.overrides(), request.metadata()));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ConsoleTestCodec.parseRequest(json, true)).getMessage().contains("unknown purpose"));
    }

    @Test
    void codecRoundTripPreservesMessagePartsAndProvenance() {
        ConsoleTestRequest request = new ConsoleTestRequest(ConsoleTestRequest.SCHEMA_VERSION,
                UUID.randomUUID().toString(), ConsoleTestRequest.RoutingMode.PURPOSE, "DEBUG_TEST", "VISION",
                List.of(), List.of(new ConsoleTestRequest.MessageEntry("vision", "creaturechat:world",
                        "user", List.of(ConsoleTestRequest.Part.text("inspect"),
                                ConsoleTestRequest.Part.image("image/png", "AA==", "low")),
                        true, 900)), LlmRouteOptions.empty(), Map.of("entityId", "npc-1"));

        ConsoleTestRequest decoded = ConsoleTestCodec.parseRequest(ConsoleTestCodec.toJson(request), false);

        assertEquals("creaturechat:world", decoded.messages().get(0).provenance());
        assertEquals(2, decoded.messages().get(0).parts().size());
        assertEquals("image", decoded.messages().get(0).parts().get(1).type());
        assertEquals("npc-1", decoded.metadata().get("entityId"));
    }

    private static ProviderSpec provider(String name, int port, String path) {
        return new ProviderSpec(name, "openai", "http://localhost:" + port + path, "model", 0.2, 32,
                200, List.of(new ProviderSpec.Credential(name + "#1", "key", 1)));
    }
}
