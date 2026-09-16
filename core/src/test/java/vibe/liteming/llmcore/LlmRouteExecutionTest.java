// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmRouteExecutionTest {
    private HttpServer server;
    private ExecutorService workers;
    private final List<LlmOrchestrator> runtimes = new ArrayList<>();
    private final CountDownLatch releaseSlow = new CountDownLatch(1);

    @BeforeEach void startServer() throws IOException {
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

    @Test void retriesThenRacesThenRevisitsTheOriginalProvider() throws Exception {
        AtomicInteger aCalls = new AtomicInteger();
        CountDownLatch competitors = new CountDownLatch(2);
        server.createContext("/A", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (aCalls.incrementAndGet() <= 4) reply(exchange, 503, "{\"error\":\"temporary\"}");
            else reply(exchange, 200, completion("recovered", "stop"));
        });
        for (String name : List.of("B", "C")) server.createContext("/" + name, exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(body.get("stream").getAsBoolean());
            competitors.countDown();
            await(competitors);
            reply(exchange, 400, "{\"error\":\"unsupported input\"}");
        });
        LlmOrchestrator core = core("A*3 > (B*0 | C*0) > A", "A", "B", "C");
        assertEquals(7, core.estimateWorstCaseBudget(request()).maxCalls());
        LlmResponse result = core.send(request()).get(20, TimeUnit.SECONDS);
        assertTrue(result.success(), result.error());
        assertEquals("recovered", result.content());
        assertEquals(5, aCalls.get());
        List<String> order = result.attempts().stream().map(LlmResponse.Attempt::provider).toList();
        assertEquals(List.of("A", "A", "A", "A"), order.subList(0, 4));
        assertEquals(Set.of("B", "C"), Set.copyOf(order.subList(4, 6)));
        assertEquals("A", order.get(6));
    }

    @Test void winningAttemptCarriesIdenticalCacheUsageThroughResponseEventAndSettlement() throws Exception {
        server.createContext("/A", exchange -> {
            readBody(exchange);
            reply(exchange, 200, """
                    {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":100,"completion_tokens":7,
                       "prompt_tokens_details":{"cached_tokens":75}}}
                    """);
        });
        List<LlmRequestLogger.AttemptEvent> events = new CopyOnWriteArrayList<>();
        List<LlmRequestAccounting.AttemptUsage> settlements = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<LlmRequestLogger.AttemptEvent> listener = events::add;
        LlmRequestLogger.addAttemptListener(listener);
        LlmRequestAccounting.install(recordingPolicy(settlements));
        try {
            LlmResponse response = core("A*0", "A").send(request()).get(5, TimeUnit.SECONDS);

            assertTrue(response.success(), response.error());
            assertEquals(LlmCacheUsage.reported(75L, null, 25L, 100L), response.cacheUsage());
            assertEquals(response.cacheUsage(), response.attempts().get(0).cacheUsage());
            assertEquals(response.cacheUsage(), events.get(0).cacheUsage());
            assertEquals(response.cacheUsage(), settlements.get(0).cacheUsage());
            assertEquals(response.provider(), settlements.get(0).provider());
            assertEquals(response.model(), settlements.get(0).model());
            assertEquals(response.credentialId(), settlements.get(0).credentialId());
            assertEquals(64, settlements.get(0).cacheDomainIdentity().length());
            assertFalse(settlements.get(0).cacheDomainIdentity().contains("test-key"));
        } finally {
            LlmRequestLogger.removeAttemptListener(listener);
        }
    }

    @Test void streamPublishesTerminalCacheUsageOnce() throws Exception {
        server.createContext("/A", exchange -> {
            readBody(exchange);
            reply(exchange, 200, "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":7,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":75}},"
                    + "\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n");
        });
        List<LlmRequestLogger.AttemptEvent> events = new CopyOnWriteArrayList<>();
        List<LlmRequestAccounting.AttemptUsage> settlements = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<LlmRequestLogger.AttemptEvent> listener = events::add;
        LlmRequestLogger.addAttemptListener(listener);
        LlmRequestAccounting.install(recordingPolicy(settlements));
        try {
            LlmResponse response = core("A*0", "A").sendStreaming(request(), ignored -> { })
                    .get(5, TimeUnit.SECONDS);
            assertTrue(response.success(), response.error());
            assertEquals(LlmCacheUsage.reported(75L, null, 25L, 100L), response.cacheUsage());
            assertEquals(1, events.size());
            assertEquals(1, settlements.size());
            assertEquals(response.cacheUsage(), events.get(0).cacheUsage());
            assertEquals(response.cacheUsage(), settlements.get(0).cacheUsage());
        } finally {
            LlmRequestLogger.removeAttemptListener(listener);
        }
    }

    @Test void raceUsesFirstCompleteBodyAndCancelsTheOtherTransportWithoutDeltas() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger settlements = new AtomicInteger();
        AtomicInteger estimatedSettlements = new AtomicInteger();
        Map<String, LlmRequestAccounting.AttemptEstimate> estimates = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, LlmRequestAccounting.AttemptUsage> usages = new java.util.concurrent.ConcurrentHashMap<>();
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                estimates.put(estimate.provider(), estimate);
                return LlmRequestAccounting.Reservation.allow(estimate.provider(), estimate.totalTokens(),
                        estimate.totalCostUnits());
            }
            @Override public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation, LlmRequestAccounting.AttemptUsage usage) {
                settlements.incrementAndGet();
                if (usage != null) {
                    usages.put(reservation.reservationId(), usage);
                    if (usage.estimatedTokens() > 0) estimatedSettlements.incrementAndGet();
                }
            }
        });
        server.createContext("/slow", exchange -> {
            assertFalse(readBody(exchange).get("stream").getAsBoolean());
            sent.incrementAndGet();
            byte[] body = completion("slow", "stop").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body, 0, 5);
            exchange.getResponseBody().flush();
            started.countDown();
            await(releaseSlow);
            try { exchange.getResponseBody().write(body, 5, body.length - 5); }
            finally { exchange.close(); }
        });
        server.createContext("/fast", exchange -> {
            assertFalse(readBody(exchange).get("stream").getAsBoolean());
            sent.incrementAndGet();
            started.countDown();
            await(started);
            reply(exchange, 200, completion("fast", "stop"));
        });
        List<String> deltas = new CopyOnWriteArrayList<>();
        LlmOrchestrator runtime = core("(slow*0 | fast*0)", "slow", "fast");
        long rawCeiling = runtime.estimateWorstCaseBudget(request()).maxTokens();
        runtime.replaceProviderProfiles(Map.of(
                "slow", new ProviderProfile("slow", ProviderCapabilities.textOnly(), new LlmCostRate(2, 4)),
                "fast", new ProviderProfile("fast", ProviderCapabilities.textOnly(), new LlmCostRate(0.5, 2))));
        assertEquals(rawCeiling, runtime.estimateWorstCaseBudget(request()).maxTokens());
        LlmResponse result = runtime.sendStreaming(request(), deltas::add).get(5, TimeUnit.SECONDS);
        assertEquals("fast", result.content());
        assertEquals(2, sent.get());
        assertTrue(deltas.isEmpty());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.finishReason().equals("cancelled")));
        assertEquals(2, settlements.get());
        assertEquals(1, estimatedSettlements.get(), "a sent loser may still be billed by its provider");
        assertEquals(estimates.get("slow").totalCostUnits(), usages.get("slow").totalCostUnits());
        assertEquals(estimates.get("slow").totalTokens(), usages.get("slow").estimatedTokens());
        assertEquals(16L, usages.get("fast").totalCostUnits()); // ceil(3 * .5) + 7 * 2
        assertEquals(10L, usages.get("fast").totalTokens());
    }

    @Test void emptyAndTruncatedRepliesCannotWinARace() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        for (String name : List.of("empty", "truncated", "valid")) server.createContext("/" + name, exchange -> {
            readBody(exchange);
            started.countDown();
            await(started);
            reply(exchange, 200, completion(name.equals("empty") ? "" : name,
                    name.equals("truncated") ? "length" : "stop"));
        });
        LlmResponse result = core("(empty*0 | truncated*0 | valid*0)", "empty", "truncated", "valid")
                .send(request()).get(5, TimeUnit.SECONDS);
        assertTrue(result.success(), result.error());
        assertEquals("valid", result.content());
    }

    @Test void retryAfterIsHonoredWhenReusingASingleCredential() throws Exception {
        List<Long> times = new CopyOnWriteArrayList<>();
        server.createContext("/A", exchange -> {
            readBody(exchange);
            times.add(System.nanoTime());
            if (times.size() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                reply(exchange, 429, "{\"error\":\"slow down\"}");
            } else reply(exchange, 200, completion("ok", "stop"));
        });
        LlmResponse result = core("A*1", "A").send(request()).get(5, TimeUnit.SECONDS);
        assertTrue(result.success(), result.error());
        assertEquals(2, times.size());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(times.get(1) - times.get(0)) >= 950);
    }

    @Test void routeDeadlineCancelsAStalledStreamWithoutStartingTheFallback() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        server.createContext("/A", exchange -> {
            readBody(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            await(releaseSlow);
            exchange.close();
        });
        server.createContext("/B", exchange -> {
            fallback.incrementAndGet();
            reply(exchange, 200, completion("wrong", "stop"));
        });
        LlmOrchestrator core = core("A*3 > B*0", "A", "B");
        core.setRoutingConfig(core.getRoutingConfig().withDefaultRoute(LlmRoute.parse("A*3 > B*0").withDeadline(1)));
        LlmResponse result = core.sendStreaming(request(), ignored -> { }).get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertTrue(result.error().contains("deadline"));
        assertEquals(0, fallback.get());
    }

    @Test void emittedStreamFailureNeverRetriesOrFallsBack() throws Exception {
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        List<LlmRequestAccounting.AttemptUsage> settlements = new CopyOnWriteArrayList<>();
        LlmRequestAccounting.install(recordingPolicy(settlements));
        server.createContext("/A", exchange -> {
            readBody(exchange);
            aCalls.incrementAndGet();
            reply(exchange, 200, "data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":7},"
                    + "\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: invalid-json\n\n");
        });
        server.createContext("/B", exchange -> {
            bCalls.incrementAndGet();
            reply(exchange, 200, completion("wrong", "stop"));
        });
        List<String> deltas = new CopyOnWriteArrayList<>();
        LlmResponse result = core("A*3 > B*0", "A", "B")
                .sendStreaming(request(), deltas::add).get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(List.of("partial"), deltas);
        assertEquals(1, aCalls.get());
        assertEquals(0, bCalls.get());
        assertEquals(3, result.promptTokens());
        assertEquals(7, result.completionTokens());
        assertEquals(1, settlements.size());
        assertEquals(3, settlements.get(0).promptTokens());
        assertEquals(7, settlements.get(0).completionTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"data: [DONE]\n\n", "data: invalid-json\n\n"})
    void unusableUnemittedStreamsSkipRetriesAndAllowFallback(String failedStream) throws Exception {
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        server.createContext("/A", exchange -> {
            readBody(exchange);
            aCalls.incrementAndGet();
            reply(exchange, 200, failedStream);
        });
        server.createContext("/B", exchange -> {
            readBody(exchange);
            bCalls.incrementAndGet();
            reply(exchange, 200, "data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        });
        List<String> deltas = new CopyOnWriteArrayList<>();
        LlmResponse result = core("A*3 > B*0", "A", "B").sendStreaming(request(), deltas::add)
                .get(5, TimeUnit.SECONDS);
        assertTrue(result.success(), result.error());
        assertEquals(List.of("recovered"), deltas);
        assertEquals(1, aCalls.get());
        assertEquals(1, bCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationStopsAStreamAndSettlesKnownUsageOnce(boolean closeRuntime) throws Exception {
        List<LlmRequestAccounting.AttemptUsage> settlements = new CopyOnWriteArrayList<>();
        LlmRequestAccounting.install(recordingPolicy(settlements));
        CountDownLatch received = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/A", exchange -> {
            readBody(exchange);
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":7},"
                    + "\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            await(releaseSlow);
            exchange.close();
        });
        LlmOrchestrator core = core("A*3 > A", "A");
        var pending = core.sendStreaming(request(), delta -> received.countDown());
        assertTrue(received.await(5, TimeUnit.SECONDS));
        if (closeRuntime) core.close();
        else assertTrue(pending.cancel(true));
        assertTrue(pending.isCompletedExceptionally());
        assertEquals(1, settlements.size());
        assertEquals(3, settlements.get(0).promptTokens());
        assertEquals(7, settlements.get(0).completionTokens());
        assertEquals(1, calls.get());
        core.close();
        assertFalse(core.send(request()).join().success());
        assertEquals(1, settlements.size());
    }

    @Test void preferredSearchFallbackReservesAfterSettlementWithinTheExchangeBudget() throws Exception {
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        List<String> settled = new CopyOnWriteArrayList<>();
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                if (inFlight.get() != 0) {
                    return LlmRequestAccounting.Reservation.deny(LlmRequestAccounting.DenyCode.CAPACITY, "previous attempt is still reserved");
                }
                int call = reservations.incrementAndGet();
                if (call > request.billingContext().maxCalls()) {
                    return LlmRequestAccounting.Reservation.deny(LlmRequestAccounting.DenyCode.CHAIN_CALLS_EXHAUSTED, "call ceiling");
                }
                inFlight.incrementAndGet();
                return LlmRequestAccounting.Reservation.allow(Integer.toString(call), estimate.totalTokens());
            }
            @Override public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation, LlmRequestAccounting.AttemptUsage usage) {
                settled.add(usage == null ? "released" : "charged");
                inFlight.decrementAndGet();
            }
        });
        server.createContext("/A", exchange -> {
            var body = readBody(exchange);
            if (body.has("web_search_options")) reply(exchange, 400, "{\"error\":\"search unavailable\"}");
            else reply(exchange, 200, completion("text fallback", "stop"));
        });
        LlmOrchestrator core = core("A*0", "A");
        core.replaceProviderProfiles(Map.of("A", new ProviderProfile("A", new ProviderCapabilities(
                Set.of("text"), Set.of("text"),
                ProviderCapabilities.HostedWebSearch.using(HostedWebSearchAdapterIds.OPENAI_CHAT)))));
        core.setCapabilityPolicy(LlmCapabilityPolicy.allowingWebSearch(List.of("CHAT")));
        var exchange = new LlmExchangeRequest(request(), LlmHostedWebSearchRequest.preferred());
        LlmCallBudget budget = core.estimateWorstCaseBudget(exchange);
        var billing = LlmBillingContext.system(LlmBillingContext.PrincipalKind.SCRIPT_SYSTEM, "search-fallback",
                budget.maxCalls(), budget.maxTokens());
        var result = core.exchange(new LlmExchangeRequest(request().withBillingContext(billing), exchange.webSearch()))
                .get(5, TimeUnit.SECONDS);
        assertTrue(result.success(), result.error());
        assertEquals(LlmExchangeResponse.WebSearchStatus.DEGRADED, result.webSearchStatus());
        assertEquals(2, reservations.get());
        assertEquals(List.of("released", "charged"), settled);
        assertEquals(0, inFlight.get());
    }

    @Test void billingDenialEndsTheRaceAndDoesNotStartLaterStages() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        for (String name : List.of("A", "B", "C")) server.createContext("/" + name, exchange -> {
            httpCalls.incrementAndGet();
            reply(exchange, 200, completion("wrong", "stop"));
        });
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                return reservations.incrementAndGet() == 1
                        ? LlmRequestAccounting.Reservation.deny(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED, "quota")
                        : LlmRequestAccounting.Reservation.allow("unexpected", 100);
            }
            @Override public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation, LlmRequestAccounting.AttemptUsage usage) { fail("denied requests have no reservation"); }
        });
        LlmResponse result = core("(A*3 | B*3) > C*0", "A", "B", "C").send(request()).get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED, result.denyCode());
        assertEquals(0, httpCalls.get());
        assertEquals(1, reservations.get());
        assertTrue(result.attempts().stream().noneMatch(attempt -> attempt.provider().equals("C")));
    }

    @Test void routeBudgetAndDraftUseEveryStageWithoutLosingPurposeLimits() {
        LlmOrchestrator core = core("A", "A", "B");
        core.setRoutingConfig(core.getRoutingConfig().withPurposeRoute("VISION", LlmRoute.parse("A*3 > (A | B) > A")));
        assertEquals(7, core.estimateMaximumRouteBudget(request()).maxCalls());
        core.setRoutingConfig(core.getRoutingConfig().withPurposeOptions("CHAT", new LlmRouteOptions(null, null, null, 12, null)));
        LlmMessageDraft draft = new LlmMessageDraft(List.of());
        assertEquals(12, core.finalizeDraftForRoute(draft, request(), LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR).hardBudgetTokens());
    }

    @Test void maximumBudgetIncludesDefaultRouteAndHigherPurposeDefaults() {
        LlmOrchestrator core = core("A*3 > (A | B) > A", "A", "B");
        core.setRoutingConfig(core.getRoutingConfig().withPurposeRoute("CHAT", LlmRoute.parse("A")));
        assertEquals(1, core.estimateWorstCaseBudget(request()).maxCalls());
        assertEquals(7, core.estimateMaximumRouteBudget(request()).maxCalls());
        core.setRoutingConfig(core.getRoutingConfig()
                .withPurposeRoute("VISION", LlmRoute.parse("B*1"))
                .withPurposeOptions("VISION", new LlmRouteOptions(null, 4096, null, null, null)));
        LlmRequest smallReply = new LlmRequest(request().messages(), List.of(), null, 8, 0,
                LlmRequestContext.chat(), LlmRouteOptions.empty());
        assertTrue(core.estimateMaximumRouteBudget(smallReply).maxTokens() >= 2L * 4096);
    }

    @Test void playerPreferenceCanOnlyReorderEnabledProvidersAndPreservesItsRoute() {
        LlmOrchestrator core = core("A > B > C", "A", "B", "C");
        UUID player = UUID.fromString("a78cc4bd-861b-45dc-87ec-4699aab476e5");
        LlmRoute preference = LlmRoute.parse("C*2 > B").withDeadline(45);
        core.setPlayerRoutePreference(player, "CHAT", preference);
        LlmRequest playerRequest = request().withBillingContext(
                LlmBillingContext.player(player.toString(), "root", 10, 10000));

        assertEquals("C*2 > B", core.resolveRoute(playerRequest).expression());
        assertEquals(45, core.resolveRoute(playerRequest).deadlineSeconds());
        assertEquals("A > B > C", core.resolveRoute(request()).expression());
        assertThrows(IllegalArgumentException.class,
                () -> core.setPlayerRoutePreference(player, "CHAT", LlmRoute.parse("D")));
        assertThrows(IllegalArgumentException.class,
                () -> core.setPlayerRoutePreference(player, "CHAT", LlmRoute.parse("C > C")));

        LlmRequest explicit = new LlmRequest(request().messages(), List.of("A"), null, null, 0,
                request().context(), LlmRouteOptions.empty(), playerRequest.billingContext());
        assertEquals(List.of("A"), core.resolveChain(explicit));
        core.replaceProviders(Map.of("A", core.getProviderSpec("A"), "B", core.getProviderSpec("B")));
        assertEquals("A > B > C", core.resolveRoute(playerRequest).expression());
    }

    private static LlmRequestAccounting.Policy recordingPolicy(List<LlmRequestAccounting.AttemptUsage> settlements) {
        return new LlmRequestAccounting.Policy() {
            @Override public LlmRequestAccounting.Reservation reserve(LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                return LlmRequestAccounting.Reservation.allow("test", estimate.totalTokens());
            }
            @Override public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation, LlmRequestAccounting.AttemptUsage usage) {
                settlements.add(usage);
            }
        };
    }

    private LlmOrchestrator core(String route, String... names) {
        Map<String, ProviderSpec> specs = new java.util.LinkedHashMap<>();
        for (String name : names) specs.put(name, new ProviderSpec(name, "openai",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name, "test-model", null, 64,
                List.of(new ProviderSpec.Credential("key", "test-key", 1))));
        LlmOrchestrator core = new LlmOrchestrator(specs);
        core.setRoutingConfig(PriorityRoutingConfig.empty().withDefaultRoute(LlmRoute.parse(route)));
        runtimes.add(core);
        return core;
    }

    private static LlmRequest request() {
        return LlmRequest.routed(List.of(new LlmMessage("user", "hello")), LlmRequestContext.chat());
    }

    private static com.google.gson.JsonObject readBody(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String completion(String text, String finishReason) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"},\"finish_reason\":\"" + finishReason
                + "\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":7}}";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test peer did not arrive");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
}
