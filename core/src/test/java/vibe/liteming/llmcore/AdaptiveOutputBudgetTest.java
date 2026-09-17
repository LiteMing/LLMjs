// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveOutputBudgetTest {
    private HttpServer server;
    private ExecutorService workers;
    private final List<LlmOrchestrator> runtimes = new ArrayList<>();
    private final CountDownLatch releaseSlow = new CountDownLatch(1);

    @BeforeEach void start() throws IOException {
        LlmRequestAccounting.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.start();
    }

    @AfterEach void close() {
        runtimes.forEach(LlmOrchestrator::close);
        releaseSlow.countDown();
        server.stop(0);
        workers.shutdownNow();
        LlmRequestAccounting.clear();
    }

    @Test void summarySeedGrowsAfterReasoningOnlyLengthAndBillsEachAttemptBeforeRetry() throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        List<String> messages = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        List<LlmRequestAccounting.AttemptUsage> usages = new CopyOnWriteArrayList<>();
        LlmOrchestrator core = core("A/m", spec("A", null, null));
        core.replaceProviderProfiles(Map.of("A", new ProviderProfile("A", ProviderCapabilities.textOnly(),
                new LlmCostRate(2, 3))));
        LlmRequest template = request("MEMORY_SUMMARY").withAdaptiveOutputBudget(8192);
        LlmCallBudget ceiling = core.estimateWorstCaseBudget(template);
        assertEquals(4, ceiling.maxCalls());
        LlmRequest request = template.withBillingContext(LlmBillingContext.system(
                LlmBillingContext.PrincipalKind.SERVER_MAINTENANCE, "summary-root", ceiling.maxCalls(), ceiling.maxTokens()));
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest attempt,
                    LlmRequestAccounting.AttemptEstimate estimate) {
                assertEquals(request.context(), attempt.context());
                assertEquals(request.billingContext(), attempt.billingContext());
                assertEquals(8192, attempt.adaptiveOutputSeedTokens());
                assertEquals(attempt.maxTokens().longValue(), estimate.outputTokens());
                events.add("reserve:" + estimate.outputTokens());
                return LlmRequestAccounting.Reservation.allow("attempt", estimate.totalTokens(), estimate.totalCostUnits());
            }
            @Override public void settle(LlmRequest attempt, LlmRequestAccounting.Reservation reservation,
                    LlmRequestAccounting.AttemptUsage usage) {
                events.add("settle:" + usage.completionTokens());
                usages.add(usage);
            }
        });
        server.createContext("/A", exchange -> {
            JsonObject body = read(exchange);
            int budget = body.get("max_tokens").getAsInt();
            budgets.add(budget);
            messages.add(body.get("messages").toString());
            events.add("http:" + budget);
            reply(exchange, completion(budgets.size() == 1 ? "" : "summary", budgets.size() == 1 ? "length" : "stop",
                    budgets.size() == 1 ? budget : 20));
        });
        LlmResponse response = core.send(request).get(5, TimeUnit.SECONDS);
        assertTrue(response.success(), response.error());
        assertEquals(List.of(8192, 9192), budgets);
        assertEquals(messages.get(0), messages.get(1));
        assertEquals(List.of("reserve:8192", "http:8192", "settle:8192",
                "reserve:9192", "http:9192", "settle:20"), events);
        assertEquals(2, response.attempts().size());
        assertEquals("length", response.attempts().get(0).finishReason());
        assertEquals(8192, usages.get(0).completionTokens());
        assertEquals(3L * 8192 + 6, usages.get(0).totalCostUnits());
        assertEquals(0, usages.get(0).estimatedTokens());
        assertTrue(ceiling.maxTokens() >= usages.stream().mapToLong(LlmRequestAccounting.AttemptUsage::totalTokens).sum());
        assertEquals(9192, core.resolveParameters(template, "A/m").maxOutputTokens());
        assertTrue(core.send(request).get(5, TimeUnit.SECONDS).success());
        assertEquals(9192, budgets.get(2));
    }

    @Test void learningIsIsolatedByPurposeProviderAndModel() throws Exception {
        server.createContext("/A", exchange -> { read(exchange); reply(exchange, completion("", "length", 1000)); });
        LlmOrchestrator core = core("A/m*0", spec("A", null, null), spec("B", null, null));
        assertFalse(core.send(request("MEMORY_SUMMARY")).get(5, TimeUnit.SECONDS).success());
        assertEquals(2000, core.resolveParameters(request("MEMORY_SUMMARY"), "A/m").maxOutputTokens());
        assertEquals(1000, core.resolveParameters(request("CHAT"), "A/m").maxOutputTokens());
        assertEquals(1000, core.resolveParameters(request("MEMORY_SUMMARY"), "A/other").maxOutputTokens());
        assertEquals(1000, core.resolveParameters(request("MEMORY_SUMMARY"), "B/m").maxOutputTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"global", "provider", "purpose", "request", "override"})
    void explicitMaximumIsFixedAndNeverLearns(String layer) throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            budgets.add(read(exchange).get("max_tokens").getAsInt());
            reply(exchange, completion("", "length", 2048));
        });
        LlmOrchestrator core = core("A/m*3", spec("A", layer.equals("provider") ? 2048 : null, null));
        LlmRouteOptions maximum = new LlmRouteOptions(null, 2048, null, null, null);
        if (layer.equals("global")) core.setGlobalDefaults(maximum);
        if (layer.equals("purpose")) core.setRoutingConfig(core.getRoutingConfig().withPurposeOptions("MEMORY_SUMMARY", maximum));
        LlmRequest base = request("MEMORY_SUMMARY").withAdaptiveOutputBudget(8192);
        LlmRequest request = new LlmRequest(base.messages(), List.of(), null,
                layer.equals("request") ? 2048 : null, 0, base.context(),
                layer.equals("override") ? maximum : LlmRouteOptions.empty(), base.billingContext(), base.adaptiveOutputSeedTokens());
        assertEquals(4, core.estimateWorstCaseBudget(request).maxCalls());
        assertFalse(core.send(request).get(5, TimeUnit.SECONDS).success());
        assertEquals(List.of(2048), budgets);
        assertEquals(1000, core.providerSnapshot().get("A").outputBudget("MEMORY_SUMMARY", "m"));
        core.setGlobalDefaults(LlmRouteOptions.empty());
        core.setRoutingConfig(core.getRoutingConfig().withPurposeOptions("MEMORY_SUMMARY", null));
        if (layer.equals("provider")) core.replaceProviders(Map.of("A", spec("A", null, null)));
        assertEquals(1000, core.resolveParameters(request("MEMORY_SUMMARY"), "A/m").maxOutputTokens());
    }

    @ParameterizedTest
    @CsvSource({"A/m,1000,4,4000,5000", "A/m*0,1000,1,1000,2000", "A/m*1,1000,2,2000,3000",
            "A/m,31000,2,32000,32000"})
    void truncationRetriesAreBoundedAndShareExplicitRouteRetryCounts(String route, int seed, int calls,
            int lastBudget, int learned) throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            budgets.add(read(exchange).get("max_tokens").getAsInt());
            reply(exchange, completion("", "length", budgets.get(budgets.size() - 1)));
        });
        LlmOrchestrator core = core(route, spec("A", null, null));
        LlmRequest request = request("CHAT").withAdaptiveOutputBudget(seed);
        assertTrue(core.estimateWorstCaseBudget(request).maxCalls() >= calls);
        LlmResponse response = core.send(request).get(5, TimeUnit.SECONDS);
        assertFalse(response.success());
        assertEquals(calls, budgets.size());
        assertEquals(lastBudget, budgets.get(budgets.size() - 1));
        assertEquals(learned, core.resolveParameters(request, "A/m").maxOutputTokens());
        assertEquals(calls, response.attempts().size());
    }

    @Test void emittedStreamLengthStopsWithoutReplayOrFallbackButLearnsForNextRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger fallbacks = new AtomicInteger();
        List<String> deltas = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            assertTrue(read(exchange).get("stream").getAsBoolean());
            calls.incrementAndGet();
            reply(exchange, stream("partial", "length", 1000));
        });
        server.createContext("/B", exchange -> { fallbacks.incrementAndGet(); reply(exchange, completion("fallback", "stop", 3)); });
        LlmOrchestrator core = core("A/m > B/m", spec("A", null, null), spec("B", null, null));
        LlmResponse response = core.sendStreaming(request("CHAT"), deltas::add).get(5, TimeUnit.SECONDS);
        assertFalse(response.success());
        assertEquals(List.of("partial"), deltas);
        assertEquals(1, calls.get());
        assertEquals(0, fallbacks.get());
        assertEquals(2000, core.resolveParameters(request("CHAT"), "A/m").maxOutputTokens());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reasoningOnlyStreamLengthRetriesAndSettlesReportedOrConservativeUsage(boolean reportedUsage) throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        List<String> deltas = new CopyOnWriteArrayList<>();
        List<LlmRequestAccounting.AttemptUsage> usages = new CopyOnWriteArrayList<>();
        LlmRequestAccounting.install(recording(usages));
        server.createContext("/A", exchange -> {
            int budget = read(exchange).get("max_tokens").getAsInt();
            budgets.add(budget);
            boolean first = budgets.size() == 1;
            reply(exchange, stream(first ? "" : "answer", first ? "length" : "stop", reportedUsage || !first ? budget : null));
        });
        LlmOrchestrator core = core("A/m", spec("A", null, null));
        LlmResponse response = core.sendStreaming(request("CHAT"), deltas::add).get(5, TimeUnit.SECONDS);
        assertTrue(response.success(), response.error());
        assertEquals(List.of(1000, 2000), budgets);
        assertEquals(List.of("answer"), deltas);
        assertEquals(2, usages.size());
        if (reportedUsage) {
            assertEquals(1000, usages.get(0).completionTokens());
            assertEquals(0, usages.get(0).estimatedTokens());
        } else {
            assertTrue(usages.get(0).estimatedTokens() >= 1000);
            assertEquals(0, usages.get(0).completionTokens());
        }
    }

    @Test void simulatedAnthropicStreamingWithholdsTruncatedBodyUntilSuccessfulRetry() throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        List<String> deltas = new CopyOnWriteArrayList<>();
        ProviderSpec openai = spec("A", null, null);
        ProviderSpec anthropic = new ProviderSpec("A", "anthropic", openai.url(), "m", null, null, openai.credentials());
        server.createContext("/A", exchange -> {
            budgets.add(read(exchange).get("max_tokens").getAsInt());
            boolean first = budgets.size() == 1;
            reply(exchange, "{\"content\":[{\"type\":\"text\",\"text\":\"" + (first ? "partial" : "complete")
                    + "\"}],\"stop_reason\":\"" + (first ? "max_tokens" : "end_turn")
                    + "\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1000}}");
        });
        LlmResponse response = core("A/m", anthropic).sendStreaming(request("CHAT"), deltas::add).get(5, TimeUnit.SECONDS);
        assertTrue(response.success(), response.error());
        assertEquals(List.of(1000, 2000), budgets);
        assertEquals(List.of("complete"), deltas);
    }

    @Test void cancelledRaceLoserCannotLearnFromItsLateLengthReply() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch slowFinished = new CountDownLatch(1);
        AtomicInteger slowCalls = new AtomicInteger();
        List<Integer> slowBudgets = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            slowBudgets.add(read(exchange).get("max_tokens").getAsInt());
            if (slowCalls.incrementAndGet() > 1) { reply(exchange, completion("next", "stop", 3)); return; }
            byte[] bytes = completion("", "length", 1000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes, 0, 5);
            exchange.getResponseBody().flush();
            started.countDown();
            await(releaseSlow);
            try { exchange.getResponseBody().write(bytes, 5, bytes.length - 5); }
            catch (IOException cancelled) { }
            finally { exchange.close(); slowFinished.countDown(); }
        });
        server.createContext("/B", exchange -> {
            read(exchange); started.countDown(); await(started); reply(exchange, completion("winner", "stop", 3));
        });
        LlmOrchestrator core = core("(A/m*0 | B/m*0)", spec("A", null, null), spec("B", null, null));
        assertEquals("winner", core.send(request("CHAT")).get(5, TimeUnit.SECONDS).content());
        releaseSlow.countDown();
        assertTrue(slowFinished.await(5, TimeUnit.SECONDS));
        core.setRoutingConfig(core.getRoutingConfig().withDefaultRoute(LlmRoute.parse("A/m*0")));
        assertTrue(core.send(request("CHAT")).get(5, TimeUnit.SECONDS).success());
        assertEquals(List.of(1000, 1000), slowBudgets);
    }

    @Test void retryKeepsFinalizedCapabilityDecisionsAndRespectsTheProviderContextWindow() throws Exception {
        List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            JsonObject body = read(exchange);
            bodies.add(body);
            reply(exchange, completion(bodies.size() < 4 ? "" : "answer", bodies.size() < 4 ? "length" : "stop", 1000));
        });
        LlmOrchestrator core = core("A/m", spec("A", null, 8000));
        LlmMessageDraft draft = new LlmMessageDraft(List.of(
                new LlmMessageDraft.Entry("system", "persona", new LlmMessage("system", "required"), true, 0),
                new LlmMessageDraft.Entry("capability", "capability", new LlmMessage("system", "capability:" + "x".repeat(1000)), false, 10),
                new LlmMessageDraft.Entry("history", "history", new LlmMessage("user", "x".repeat(5000)), false, 1)));
        LlmMessageFinalization finalized = core.finalizeDraftForRoute(draft, request("CHAT"), null);
        assertTrue(finalized.withinBudget());
        assertEquals(4000, finalized.hardBudgetTokens());
        assertTrue(finalized.decisions().get(1).included());
        assertFalse(finalized.decisions().get(2).included());
        assertTrue(core.send(LlmRequest.routed(finalized.messages(), request("CHAT").context())).get(5, TimeUnit.SECONDS).success());
        assertEquals(4, bodies.size());
        for (JsonObject body : bodies) {
            assertEquals(bodies.get(0).get("messages"), body.get("messages"));
            assertTrue(finalized.estimatedInputTokens() + body.get("max_tokens").getAsInt() <= 8000);
        }
    }

    @Test void contextCeilingAndBudgetDenialStopAdditionalAttempts() throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            int budget = read(exchange).get("max_tokens").getAsInt();
            budgets.add(budget); reply(exchange, completion("", "length", budget));
        });
        LlmOrchestrator core = core("A/m > B/m", spec("A", null, 1020), spec("B", null, null));
        core.setRoutingConfig(core.getRoutingConfig().withDefaultRoute(LlmRoute.parse("A/m")));
        assertFalse(core.send(request("CHAT")).get(5, TimeUnit.SECONDS).success());
        assertEquals(List.of(1000, 1011), budgets);
        budgets.clear();
        LlmOrchestrator noCapacity = core("A/m", spec("A", null, 9));
        LlmResponse exhausted = noCapacity.send(request("CHAT")).get(5, TimeUnit.SECONDS);
        assertFalse(exhausted.success());
        assertTrue(exhausted.error().contains("no output capacity"));
        assertTrue(budgets.isEmpty());
        AtomicInteger reservations = new AtomicInteger();
        List<LlmRequestAccounting.AttemptUsage> usages = new CopyOnWriteArrayList<>();
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                return reservations.incrementAndGet() == 1 ? LlmRequestAccounting.Reservation.allow("sent", estimate.totalTokens())
                        : LlmRequestAccounting.Reservation.deny(LlmRequestAccounting.DenyCode.CHAIN_TOKENS_EXHAUSTED, "hard root ceiling");
            }
            @Override public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation,
                    LlmRequestAccounting.AttemptUsage usage) { usages.add(usage); }
        });
        core = core("A/m > B/m", spec("A", null, null), spec("B", null, null));
        LlmResponse denied = core.send(request("CHAT")).get(5, TimeUnit.SECONDS);
        assertEquals(LlmRequestAccounting.DenyCode.CHAIN_TOKENS_EXHAUSTED, denied.denyCode());
        assertEquals(List.of(1000), budgets);
        assertEquals(1, usages.size());
        assertEquals(1000, usages.get(0).completionTokens());
    }

    @Test void causalCeilingIncludesAllParallelCredentialTruncationRetries() {
        ProviderSpec original = spec("A", null, null);
        ProviderSpec parallel = new ProviderSpec("A", "openai", original.url(), "m", null, null, null,
                List.of(new ProviderSpec.Credential("one", "one", 1), new ProviderSpec.Credential("two", "two", 1)),
                List.of("m"), ProviderSpec.RequestMode.PARALLEL);
        assertEquals(8, core("A/m", parallel).estimateWorstCaseBudget(request("CHAT")).maxCalls());
    }

    private LlmOrchestrator core(String route, ProviderSpec... specs) {
        Map<String, ProviderSpec> providers = new java.util.LinkedHashMap<>();
        for (ProviderSpec spec : specs) providers.put(spec.name(), spec);
        LlmOrchestrator core = new LlmOrchestrator(providers);
        core.setGlobalDefaults(LlmRouteOptions.empty());
        core.setRoutingConfig(PriorityRoutingConfig.empty().withDefaultRoute(LlmRoute.parse(route)));
        runtimes.add(core);
        return core;
    }

    private ProviderSpec spec(String name, Integer maximum, Integer window) {
        return new ProviderSpec(name, "openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name,
                "m", null, maximum, window, List.of(new ProviderSpec.Credential("key", "local-test", 1)),
                List.of("m", "other"), ProviderSpec.RequestMode.ROTATION);
    }

    private static LlmRequest request(String purpose) {
        return LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                new LlmRequestContext("logical-request", purpose, "", "", "", "", "", false));
    }

    private static LlmRequestAccounting.Policy recording(List<LlmRequestAccounting.AttemptUsage> usages) {
        return new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                return LlmRequestAccounting.Reservation.allow("attempt", estimate.totalTokens(), estimate.totalCostUnits());
            }
            @Override public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation,
                    LlmRequestAccounting.AttemptUsage usage) { usages.add(usage); }
        };
    }

    private static JsonObject read(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String completion(String content, String finish, int outputTokens) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\",\"reasoning_content\":\"thinking\"},"
                + "\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":" + outputTokens
                + ",\"completion_tokens_details\":{\"reasoning_tokens\":" + (content.isEmpty() ? outputTokens : 0) + "}}}";
    }

    private static String stream(String content, String finish, Integer outputTokens) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\",\"reasoning_content\":\"thinking\"},"
                + "\"finish_reason\":\"" + finish + "\"}]}\n\n"
                + (outputTokens == null ? "" : "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":" + outputTokens + "}}\n\n")
                + "data: [DONE]\n\n";
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test peer did not arrive");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException(interrupted);
        }
    }
}
