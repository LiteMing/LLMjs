package vibe.liteming.llmjs.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vibe.liteming.llmcore.LlmBillingContext;
import vibe.liteming.llmcore.LlmMessage;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestAccounting;
import vibe.liteming.llmcore.LlmRequestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalBudgetServiceTest {
    @Test
    void reservesAtomicallyAndBlocksPlayerAtFiniteDefault(@TempDir Path root) {
        AtomicLong limit = new AtomicLong(10L);
        PersonalBudgetService service = service(root, limit::get);
        UUID player = UUID.randomUUID();
        LlmRequest first = playerRequest(player, "root-a", 1, 10L);

        LlmRequestAccounting.Reservation reservation = service.reserve(first, estimate(6, 4));

        assertTrue(reservation.allowed());
        assertEquals(10L, service.status(player).reservedTokens());
        LlmRequestAccounting.Reservation denied = service.reserve(
                playerRequest(player, "root-b", 1, 10L), estimate(1, 1));
        assertFalse(denied.allowed());
        assertEquals(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED, denied.denyCode());
        service.settle(first, reservation, usage(6, 4, 0));
        assertEquals(10L, service.status(player).totalTokens());
        assertTrue(service.status(player).exhausted());
        assertTrue(service.reset(player));
        assertTrue(service.reserve(playerRequest(player, "root-d", 1, 10L), estimate(1, 1)).allowed());
    }

    @Test
    void playerLimitSupportsUnsetUnlimitedDisabledAndFinite(@TempDir Path root) {
        AtomicLong serverDefault = new AtomicLong(0L);
        PersonalBudgetService service = service(root, serverDefault::get);
        UUID player = UUID.randomUUID();

        assertDeniedBudget(service.reserve(playerRequest(player, "unset-disabled", 1, 100L), estimate(1, 1)));
        assertDeniedBudget(service.preflight(
                LlmBillingContext.player(player.toString(), "preflight-disabled", 1, 100L)));
        assertTrue(service.status(player).inheritedLimit());

        assertTrue(service.setLimit(player, -1L));
        settle(service, playerRequest(player, "explicit-unlimited", 1, 100L),
                estimate(3, 2), usage(3, 2, 0));
        assertTrue(service.status(player).unlimited());
        assertFalse(service.status(player).inheritedLimit());

        assertTrue(service.setLimit(player, 0L));
        assertDeniedBudget(service.reserve(playerRequest(player, "explicit-disabled", 1, 100L), estimate(1, 1)));
        assertTrue(service.status(player).disabled());

        assertTrue(service.setLimit(player, 10L));
        assertTrue(service.reserve(playerRequest(player, "finite", 1, 100L), estimate(2, 2)).allowed());

        assertTrue(service.setLimit(player, null));
        assertTrue(service.status(player).inheritedLimit());
        assertDeniedBudget(service.reserve(playerRequest(player, "inherit-disabled", 1, 100L), estimate(1, 1)));

        serverDefault.set(-1L);
        assertTrue(service.reserve(playerRequest(player, "inherit-unlimited", 1, 100L), estimate(1, 1)).allowed());
    }

    @Test
    void resettingUsagePreservesPlayerOverride(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> -1L);
        UUID player = UUID.randomUUID();
        assertTrue(service.setLimit(player, 20L));
        settle(service, playerRequest(player, "root", 1, 100L), estimate(3, 2), usage(3, 2, 0));

        assertTrue(service.reset(player));

        PersonalBudgetService.Status status = service.status(player);
        assertEquals(0L, status.totalTokens());
        assertEquals(20L, status.limitTokens());
        assertFalse(status.inheritedLimit());
    }

    @Test
    void readsSchemaOneAsUnsetAndWritesSchemaTwo(@TempDir Path root) throws IOException {
        Path file = root.resolve("personal-budget.json");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "players": [{
                    "playerId": "%s",
                    "promptTokens": 3,
                    "completionTokens": 2,
                    "estimatedTokens": 0,
                    "updatedAtMs": 10
                  }]
                }
                """.formatted(player));
        PersonalBudgetService service = new PersonalBudgetService(() -> 100L);
        service.open(file);

        assertEquals(5L, service.status(player).totalTokens());
        assertTrue(service.status(player).inheritedLimit());
        assertTrue(service.setLimit(player, -1L));
        String persisted = Files.readString(file);
        assertTrue(persisted.contains("\"schemaVersion\": 2"));
        assertTrue(persisted.contains("\"limitTokens\": -1"));
    }

    @Test
    void expiredPendingReservationIsReleasedAndLateSettleIsIgnored(@TempDir Path root) {
        AtomicLong now = new AtomicLong(1_000L);
        PersonalBudgetService service = new PersonalBudgetService(() -> 10L, now::get, 100L);
        service.open(root.resolve("personal-budget.json"));
        UUID player = UUID.randomUUID();
        LlmRequest request = playerRequest(player, "stale-root", 1, 10L);
        LlmRequestAccounting.Reservation reservation = service.reserve(request, estimate(5, 5));
        assertTrue(reservation.allowed());

        now.addAndGet(101L);
        assertEquals(0L, service.status(player).reservedTokens());
        assertTrue(service.reserve(playerRequest(player, "fresh-root", 1, 10L), estimate(1, 1)).allowed());

        service.settle(request, reservation, usage(5, 5, 0));
        assertEquals(0L, service.status(player).totalTokens());
    }

    @Test
    void causalChainEnforcesCallsAndTokensAcrossRequests(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> -1L);
        LlmRequest first = systemRequest("shared-root", 2, 10L);
        LlmRequestAccounting.Reservation one = service.reserve(first, estimate(3, 2));
        assertTrue(one.allowed());
        service.settle(first, one, usage(3, 2, 0));

        LlmRequest second = systemRequest("shared-root", 2, 10L);
        LlmRequestAccounting.Reservation two = service.reserve(second, estimate(3, 2));
        assertTrue(two.allowed());
        service.settle(second, two, usage(0, 0, 5));

        LlmRequestAccounting.Reservation calls = service.reserve(
                systemRequest("shared-root", 3, 20L), estimate(1, 1));
        assertEquals(LlmRequestAccounting.DenyCode.CHAIN_CALLS_EXHAUSTED, calls.denyCode());
        LlmRequestAccounting.Reservation tokens = service.reserve(
                systemRequest("token-root", 3, 5L), estimate(3, 3));
        assertEquals(LlmRequestAccounting.DenyCode.CHAIN_TOKENS_EXHAUSTED, tokens.denyCode());
    }

    @Test
    void identicalRootIdsAreIsolatedByBillingPrincipal(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> -1L);
        LlmRequest ambient = request(LlmBillingContext.system(
                LlmBillingContext.PrincipalKind.SERVER_AMBIENT, "shared", 1, 10L));
        LlmRequest maintenance = request(LlmBillingContext.system(
                LlmBillingContext.PrincipalKind.SERVER_MAINTENANCE, "shared", 1, 10L));

        settle(service, ambient, estimate(2, 1), usage(2, 1, 0));

        assertTrue(service.reserve(maintenance, estimate(2, 1)).allowed());
    }

    @Test
    void unlimitedModeStillRecordsAndPersistsUsage(@TempDir Path root) {
        Path file = root.resolve("personal-budget.json");
        UUID player = UUID.randomUUID();
        PersonalBudgetService service = new PersonalBudgetService(() -> -1L);
        service.open(file);
        settle(service, playerRequest(player, "root", 1, 100L), estimate(12, 3), usage(12, 3, 0));

        PersonalBudgetService reloaded = new PersonalBudgetService(() -> -1L);
        reloaded.open(file);
        assertEquals(15L, reloaded.status(player).totalTokens());
        assertTrue(reloaded.status(player).unlimited());
        assertFalse(reloaded.status(player).exhausted());
    }

    @Test
    void providerUsageFallbackRemainsMarkedEstimated(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> 1_000L);
        UUID player = UUID.randomUUID();
        settle(service, playerRequest(player, "root", 1, 100L), estimate(20, 10), usage(0, 0, 30));

        assertEquals(30L, service.status(player).estimatedTokens());
        assertEquals(30L, service.status(player).totalTokens());
    }

    @Test
    void systemPrincipalsDoNotCreatePlayerCharges(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> 0L);
        LlmRequest request = systemRequest("ambient-root", 1, 100L);

        settle(service, request, estimate(12, 3), usage(12, 3, 0));

        assertTrue(service.list().isEmpty());
        assertEquals(LlmBillingContext.PrincipalKind.SERVER_AMBIENT,
                service.principalUsage().get(0).principalKind());
        assertEquals(15L, service.principalUsage().get(0).totalTokens());
    }

    @Test
    void unspecifiedAndMalformedPlayerBillingFailClosed(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> -1L);
        LlmRequest unspecified = LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                LlmRequestContext.chat());
        LlmRequestAccounting.Reservation unspecifiedDenied = service.reserve(unspecified, estimate(1, 1));
        assertFalse(unspecifiedDenied.allowed());
        assertEquals(LlmRequestAccounting.DenyCode.INVALID_CONTEXT, unspecifiedDenied.denyCode());

        LlmRequest malformed = request(LlmBillingContext.player("not-a-uuid", "root", 1, 100L));
        LlmRequestAccounting.Reservation malformedDenied = service.reserve(malformed, estimate(1, 1));
        assertFalse(malformedDenied.allowed());
        assertEquals(LlmRequestAccounting.DenyCode.INVALID_CONTEXT, malformedDenied.denyCode());
    }

    @Test
    void corruptUsageStoreFailsClosedEvenWhenDefaultIsUnlimited(@TempDir Path root) throws IOException {
        Path file = root.resolve("personal-budget.json");
        Files.writeString(file, "{bad-json");
        PersonalBudgetService service = new PersonalBudgetService(() -> -1L);
        service.open(file);

        LlmRequestAccounting.Reservation denied = service.reserve(
                playerRequest(UUID.randomUUID(), "player-root", 1, 100L), estimate(1, 1));

        assertFalse(denied.allowed());
        assertEquals(LlmRequestAccounting.DenyCode.STORAGE_UNAVAILABLE, denied.denyCode());
        assertTrue(service.reserve(systemRequest("system-root", 1, 100L), estimate(1, 1)).allowed());
        assertFalse(service.storageAvailable());
        assertEquals("{bad-json", Files.readString(file));
        assertFalse(service.reset(UUID.randomUUID()));
    }

    @Test
    void runtimePersistenceFailureBlocksLaterPlayerRequests(@TempDir Path root) throws IOException {
        Path nonDirectory = root.resolve("not-a-directory");
        Files.writeString(nonDirectory, "occupied");
        PersonalBudgetService service = new PersonalBudgetService(() -> -1L);
        service.open(nonDirectory.resolve("personal-budget.json"));
        UUID player = UUID.randomUUID();
        LlmRequest request = playerRequest(player, "root", 1, 100L);

        settle(service, request, estimate(4, 2), usage(4, 2, 0));

        assertFalse(service.status(player).storageAvailable());
        LlmRequestAccounting.Reservation denied = service.reserve(
                playerRequest(player, "next", 1, 100L), estimate(1, 1));
        assertEquals(LlmRequestAccounting.DenyCode.STORAGE_UNAVAILABLE, denied.denyCode());
        assertFalse(service.reset(player));
    }

    @Test
    void playerStatusPayloadContainsOnlyOwnAggregateUsage(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> 100L);
        UUID player = UUID.randomUUID();
        settle(service, playerRequest(player, "root", 1, 100L), estimate(12, 3), usage(12, 3, 0));

        var status = service.statusJson(player);

        assertEquals(13, status.size());
        assertEquals(15L, status.get("totalTokens").getAsLong());
        assertEquals(1L, status.get("requestCount").getAsLong());
        assertEquals(15L, status.get("averageTokens").getAsLong());
        assertTrue(status.get("limitInherited").getAsBoolean());
        assertFalse(status.has("playerId"));
        assertNull(status.get("players"));
    }

    private static PersonalBudgetService service(Path root, java.util.function.LongSupplier limit) {
        PersonalBudgetService service = new PersonalBudgetService(limit);
        service.open(root.resolve("personal-budget.json"));
        return service;
    }

    private static void assertDeniedBudget(LlmRequestAccounting.Reservation reservation) {
        assertFalse(reservation.allowed());
        assertEquals(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED, reservation.denyCode());
    }

    private static LlmRequest playerRequest(UUID player, String root, int maxCalls, long maxTokens) {
        return request(LlmBillingContext.player(player.toString(), root, maxCalls, maxTokens));
    }

    private static LlmRequest systemRequest(String root, int maxCalls, long maxTokens) {
        return request(LlmBillingContext.system(LlmBillingContext.PrincipalKind.SERVER_AMBIENT,
                root, maxCalls, maxTokens));
    }

    private static LlmRequest request(LlmBillingContext billing) {
        return LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                new LlmRequestContext("request", "CHAT", "", "", "", "", "", false,
                        "", "", "", "", "player"), billing);
    }

    private static LlmRequestAccounting.AttemptEstimate estimate(long input, long output) {
        return new LlmRequestAccounting.AttemptEstimate("provider", input, output);
    }

    private static LlmRequestAccounting.AttemptUsage usage(long prompt, long completion, long estimated) {
        return new LlmRequestAccounting.AttemptUsage(prompt, completion, estimated);
    }

    private static void settle(PersonalBudgetService service, LlmRequest request,
            LlmRequestAccounting.AttemptEstimate estimate, LlmRequestAccounting.AttemptUsage usage) {
        LlmRequestAccounting.Reservation reservation = service.reserve(request, estimate);
        assertTrue(reservation.allowed(), reservation.reason());
        service.settle(request, reservation, usage);
    }
}
