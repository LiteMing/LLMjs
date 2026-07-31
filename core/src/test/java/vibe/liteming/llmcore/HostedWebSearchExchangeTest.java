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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedWebSearchExchangeTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        LlmRequestAccounting.clear();
    }

    @Test
    void requiredSearchDeniedByPurposePolicyMakesNoAttemptOrReservation() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        server = server(Map.of("/search", response(requests,
                "{\"choices\":[{\"message\":{\"content\":\"unused\"}}]}")));
        LlmOrchestrator orchestrator = orchestrator(Map.of("search", spec("search", "openai", "/search")));
        orchestrator.replaceProviderProfiles(Map.of("search", searchProfile("search",
                HostedWebSearchAdapterIds.OPENAI_CHAT)));
        LlmRequestAccounting.install(countingPolicy(reservations));

        LlmExchangeResponse result = orchestrator.exchange(required(request(List.of("search")))).join();

        assertFalse(result.success());
        assertEquals(LlmExchangeResponse.WebSearchStatus.POLICY_DISABLED, result.webSearchStatus());
        assertEquals(LlmExchangeResponse.ErrorCode.FEATURE_DISABLED, result.errorCode());
        assertEquals(0, requests.get());
        assertEquals(0, reservations.get());
        assertTrue(result.legacyResponse().attempts().isEmpty());
    }

    @Test
    void skipsUndeclaredProviderBeforeAccountingAndReturnsOpenAiSources() throws Exception {
        AtomicInteger skippedRequests = new AtomicInteger();
        AtomicInteger searchRequests = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        server = server(Map.of(
                "/skipped", response(skippedRequests,
                        "{\"choices\":[{\"message\":{\"content\":\"wrong\"}}]}"),
                "/search", exchange -> {
                    searchRequests.incrementAndGet();
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    write(exchange, """
                            {"choices":[{"message":{"content":"answer","annotations":[
                              {"type":"url_citation","url_citation":{"url":"https://example.test/a","title":"A","start_index":0,"end_index":6}}
                            ]}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}
                            """);
                }));
        Map<String, ProviderSpec> specs = new LinkedHashMap<>();
        specs.put("skipped", spec("skipped", "openai", "/skipped"));
        specs.put("search", spec("search", "openai", "/search"));
        LlmOrchestrator orchestrator = orchestrator(specs);
        orchestrator.replaceProviderProfiles(Map.of("search", searchProfile("search",
                HostedWebSearchAdapterIds.OPENAI_CHAT)));
        allowChatSearch(orchestrator);
        LlmRequestAccounting.install(countingPolicy(reservations));

        LlmExchangeResponse result = orchestrator.exchange(required(request(List.of("skipped", "search")))).join();

        assertTrue(result.success());
        assertEquals(LlmExchangeResponse.WebSearchStatus.USED, result.webSearchStatus());
        assertEquals(1, result.webSearchUses());
        assertEquals("https://example.test/a", result.sources().get(0).uri());
        assertEquals(0, result.sources().get(0).startIndex());
        assertEquals(6, result.sources().get(0).endIndex());
        assertEquals(0, skippedRequests.get());
        assertEquals(1, searchRequests.get());
        assertEquals(1, reservations.get());
        assertTrue(bodies.get(0).contains("\"web_search_options\":{}"));
        assertEquals(LlmRoutingDecision.Code.WEB_SEARCH_NOT_DECLARED,
                result.routingDecisions().get(0).code());
    }

    @Test
    void parsesAnthropicSearchWhenTextIsNotTheFirstBlock() throws Exception {
        List<String> bodies = new ArrayList<>();
        server = server(Map.of("/anthropic", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, """
                    {"content":[
                      {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"news"}},
                      {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[
                        {"type":"web_search_result","url":"https://example.test/news","title":"News","page_age":"today"}
                      ]},
                      {"type":"text","text":"First ","citations":[{"type":"web_search_result_location","url":"https://example.test/news","title":"News","cited_text":"fact"}]},
                      {"type":"text","text":"answer"}
                    ],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}
                    """);
        }));
        LlmOrchestrator orchestrator = configured("anthropic", "claude",
                HostedWebSearchAdapterIds.ANTHROPIC_MESSAGES, "/anthropic");

        LlmExchangeResponse result = orchestrator.exchange(required(request(List.of("anthropic")))).join();

        assertTrue(result.success());
        assertEquals("First answer", result.legacyResponse().content());
        assertEquals(1, result.webSearchUses());
        assertEquals(1, result.sources().size());
        assertTrue(bodies.get(0).contains("\"type\":\"web_search_20250305\""));
    }

    @Test
    void parsesGeminiGroundingAndConcatenatesVisibleParts() throws Exception {
        List<String> bodies = new ArrayList<>();
        server = server(Map.of("/gemini", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, """
                    {"candidates":[{"content":{"parts":[
                      {"text":"hidden","thought":true},{"text":"Grounded "},{"text":"answer"}
                    ]},"finishReason":"STOP","groundingMetadata":{
                      "webSearchQueries":["query"],
                      "groundingChunks":[{"web":{"uri":"https://example.test/g","title":"Grounding"}}]
                    }}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}
                    """);
        }));
        LlmOrchestrator orchestrator = configured("gemini", "gemini",
                HostedWebSearchAdapterIds.GEMINI_GENERATE_CONTENT, "/gemini");

        LlmExchangeResponse result = orchestrator.exchange(required(request(List.of("gemini")))).join();

        assertTrue(result.success());
        assertEquals("Grounded answer", result.legacyResponse().content());
        assertEquals("https://example.test/g", result.sources().get(0).uri());
        assertTrue(bodies.get(0).contains("\"googleSearch\":{}"));
    }

    @Test
    void usesDashScopeDialectForQwenOrThirdPartyOpenAiChat() throws Exception {
        List<String> bodies = new ArrayList<>();
        server = server(Map.of("/qwen", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, """
                    {"choices":[{"message":{"content":"qwen answer"}}],
                     "usage":{"prompt_tokens":4,"completion_tokens":2,"plugins":{"search":{"count":1}}},
                     "search_info":{"search_results":[{"index":"1","title":"Result","url":"https://example.test/q","site_name":"Example"}]}}
                    """);
        }));
        LlmOrchestrator orchestrator = configured("qwen", "openai",
                HostedWebSearchAdapterIds.DASHSCOPE_OPENAI_CHAT, "/qwen");

        LlmExchangeResponse result = orchestrator.exchange(required(request(List.of("qwen")))).join();

        assertTrue(result.success());
        assertEquals(1, result.webSearchUses());
        assertEquals("Example", result.sources().get(0).metadata().get("siteName"));
        assertTrue(bodies.get(0).contains("\"enable_search\":true"));
        assertTrue(bodies.get(0).contains("\"enable_source\":true"));
    }

    @Test
    void requiredSearchFallsBackWhenFirstProviderReturnsTextWithoutEvidence() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        server = server(Map.of(
                "/first", response(first, "{\"choices\":[{\"message\":{\"content\":\"unguarded\"}}]}"),
                "/second", response(second, """
                        {"choices":[{"message":{"content":"grounded","annotations":[
                          {"type":"url_citation","url_citation":{"url":"https://example.test/source","title":"Source"}}
                        ]}}]}
                        """)));
        Map<String, ProviderSpec> specs = new LinkedHashMap<>();
        specs.put("first", spec("first", "openai", "/first"));
        specs.put("second", spec("second", "openai", "/second"));
        LlmOrchestrator orchestrator = orchestrator(specs);
        orchestrator.replaceProviderProfiles(Map.of(
                "first", searchProfile("first", HostedWebSearchAdapterIds.OPENAI_CHAT),
                "second", searchProfile("second", HostedWebSearchAdapterIds.OPENAI_CHAT)));
        allowChatSearch(orchestrator);

        LlmExchangeResponse result = orchestrator.exchange(required(request(List.of("first", "second")))).join();

        assertTrue(result.success());
        assertEquals("second", result.legacyResponse().provider());
        assertEquals(2, result.legacyResponse().attempts().size());
        assertFalse(result.legacyResponse().attempts().get(0).success());
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void preferredSearchDegradesExplicitlyAndLegacySendNeverInjectsSearchFields() throws Exception {
        List<String> bodies = new ArrayList<>();
        server = server(Map.of("/deepseek", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, "{\"choices\":[{\"message\":{\"content\":\"plain text\"}}]}");
        }));
        LlmOrchestrator orchestrator = orchestrator(Map.of("deepseek",
                spec("deepseek", "openai", "/deepseek")));
        allowChatSearch(orchestrator);

        LlmExchangeResponse preferred = orchestrator.exchange(new LlmExchangeRequest(
                request(List.of("deepseek")), LlmHostedWebSearchRequest.preferred())).join();
        LlmResponse legacy = orchestrator.send(request(List.of("deepseek"))).join();

        assertTrue(preferred.success());
        assertEquals(LlmExchangeResponse.WebSearchStatus.DEGRADED, preferred.webSearchStatus());
        assertEquals(List.of("web_search"), preferred.degradedFeatures());
        assertTrue(legacy.success());
        assertEquals(2, bodies.size());
        assertTrue(bodies.stream().noneMatch(body -> body.contains("web_search_options")
                || body.contains("enable_search") || body.contains("googleSearch")
                || body.contains("web_search_20250305")));
    }

    private LlmOrchestrator configured(String name, String format, String adapter, String path) {
        LlmOrchestrator orchestrator = orchestrator(Map.of(name, spec(name, format, path)));
        orchestrator.replaceProviderProfiles(Map.of(name, searchProfile(name, adapter)));
        allowChatSearch(orchestrator);
        return orchestrator;
    }

    private LlmOrchestrator orchestrator(Map<String, ProviderSpec> specs) {
        return new LlmOrchestrator(specs);
    }

    private ProviderSpec spec(String name, String format, String path) {
        return new ProviderSpec(name, format, "http://localhost:" + server.getAddress().getPort() + path,
                "model", 0.0, 64, List.of(new ProviderSpec.Credential(name + "-key", "secret", 1)));
    }

    private static ProviderProfile searchProfile(String name, String adapter) {
        return new ProviderProfile(name, new ProviderCapabilities(Set.of("text"), Set.of("text"),
                ProviderCapabilities.HostedWebSearch.using(adapter)));
    }

    private static LlmRequest request(List<String> chain) {
        return new LlmRequest(List.of(new LlmMessage("user", "question")), chain,
                0.0, 64, 5, LlmRequestContext.chat());
    }

    private static LlmExchangeRequest required(LlmRequest request) {
        return new LlmExchangeRequest(request, LlmHostedWebSearchRequest.required());
    }

    private static void allowChatSearch(LlmOrchestrator orchestrator) {
        orchestrator.setCapabilityPolicy(LlmCapabilityPolicy.allowingWebSearch(List.of("CHAT")));
    }

    private static LlmRequestAccounting.Policy countingPolicy(AtomicInteger reservations) {
        return new LlmRequestAccounting.Policy() {
            @Override
            public LlmRequestAccounting.Reservation reserve(LlmRequest request,
                    LlmRequestAccounting.AttemptEstimate estimate) {
                reservations.incrementAndGet();
                return LlmRequestAccounting.Reservation.allow("test", estimate.totalTokens());
            }

            @Override
            public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation,
                    LlmRequestAccounting.AttemptUsage usage) {
            }
        };
    }

    private HttpServer server(Map<String, com.sun.net.httpserver.HttpHandler> handlers) throws Exception {
        HttpServer result = HttpServer.create(new InetSocketAddress(0), 0);
        handlers.forEach(result::createContext);
        result.start();
        return result;
    }

    private static com.sun.net.httpserver.HttpHandler response(AtomicInteger count, String body) {
        return exchange -> {
            count.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            write(exchange, body);
        };
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
