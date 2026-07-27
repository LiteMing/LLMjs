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
    void reservesAtomicallyAndBlocksPlayerAtLimit(@TempDir Path root) {
        AtomicLong limit = new AtomicLong(10L);
        PersonalBudgetService service = service(root, limit::get);
        UUID player = UUID.randomUUID();
        LlmRequest first = playerRequest(player, "root-a", 1, 10L);

        LlmRequestAccounting.Reservation reservation = service.reserve(first, estimate(6, 4));

        assertTrue(reservation.allowed());
        assertEquals(10L, service.status(player).reservedTokens());
        assertFalse(service.reserve(playerRequest(player, "root-b", 1, 10L), estimate(1, 1)).allowed());
        service.settle(first, reservation, usage(6, 4, 0));
        assertEquals(10L, service.status(player).totalTokens());
        assertTrue(service.status(player).exhausted());
        assertFalse(service.reserve(playerRequest(player, "root-c", 1, 10L), estimate(1, 1)).allowed());
        assertTrue(service.reset(player));
        assertTrue(service.reserve(playerRequest(player, "root-d", 1, 10L), estimate(1, 1)).allowed());
    }

    @Test
    void causalChainEnforcesCallsAndTokensAcrossRequests(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> 0L);
        LlmRequest first = systemRequest("shared-root", 2, 10L);
        LlmRequestAccounting.Reservation one = service.reserve(first, estimate(3, 2));
        assertTrue(one.allowed());
        service.settle(first, one, usage(3, 2, 0));

        LlmRequest second = systemRequest("shared-root", 2, 10L);
        LlmRequestAccounting.Reservation two = service.reserve(second, estimate(3, 2));
        assertTrue(two.allowed());
        service.settle(second, two, usage(0, 0, 5));

        assertEquals("Causal chain call budget exhausted",
                service.reserve(systemRequest("shared-root", 3, 20L), estimate(1, 1)).reason());
        assertEquals("Causal chain token budget exhausted",
                service.reserve(systemRequest("token-root", 3, 5L), estimate(3, 3)).reason());
    }

    @Test
    void unlimitedModeStillRecordsAndPersistsUsage(@TempDir Path root) {
        Path file = root.resolve("personal-budget.json");
        UUID player = UUID.randomUUID();
        PersonalBudgetService service = new PersonalBudgetService(() -> 0L);
        service.open(file);
        settle(service, playerRequest(player, "root", 1, 100L), estimate(12, 3), usage(12, 3, 0));

        PersonalBudgetService reloaded = new PersonalBudgetService(() -> 0L);
        reloaded.open(file);
        assertEquals(15L, reloaded.status(player).totalTokens());
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
        PersonalBudgetService service = service(root, () -> 1L);
        LlmRequest request = systemRequest("ambient-root", 1, 100L);

        settle(service, request, estimate(12, 3), usage(12, 3, 0));

        assertTrue(service.list().isEmpty());
    }

    @Test
    void unspecifiedBillingFailsClosed(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> 0L);
        LlmRequest request = LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                LlmRequestContext.chat());

        LlmRequestAccounting.Reservation denied = service.reserve(request, estimate(1, 1));

        assertFalse(denied.allowed());
        assertEquals("LLM billing context is unspecified", denied.reason());
    }

    @Test
    void corruptUsageStoreFailsClosedOnlyForLimitedPlayers(@TempDir Path root) throws IOException {
        Path file = root.resolve("personal-budget.json");
        Files.writeString(file, "{bad-json");
        PersonalBudgetService service = new PersonalBudgetService(() -> 100L);
        service.open(file);

        LlmRequestAccounting.Reservation denied = service.reserve(
                playerRequest(UUID.randomUUID(), "player-root", 1, 100L), estimate(1, 1));

        assertFalse(denied.allowed());
        assertEquals("Personal token budget storage is unavailable", denied.reason());
        assertTrue(service.reserve(systemRequest("system-root", 1, 100L), estimate(1, 1)).allowed());
        assertFalse(service.storageAvailable());
        assertEquals("{bad-json", Files.readString(file));
        assertFalse(service.reset(UUID.randomUUID()));
    }

    @Test
    void runtimePersistenceFailureBlocksLaterLimitedPlayerRequests(@TempDir Path root) throws IOException {
        Path nonDirectory = root.resolve("not-a-directory");
        Files.writeString(nonDirectory, "occupied");
        PersonalBudgetService service = new PersonalBudgetService(() -> 100L);
        service.open(nonDirectory.resolve("personal-budget.json"));
        UUID player = UUID.randomUUID();
        LlmRequest request = playerRequest(player, "root", 1, 100L);

        settle(service, request, estimate(4, 2), usage(4, 2, 0));

        assertFalse(service.status(player).storageAvailable());
        assertFalse(service.reserve(playerRequest(player, "next", 1, 100L), estimate(1, 1)).allowed());
        assertFalse(service.reset(player));
    }

    @Test
    void playerStatusPayloadContainsOnlyOwnAggregateUsage(@TempDir Path root) {
        PersonalBudgetService service = service(root, () -> 100L);
        UUID player = UUID.randomUUID();
        settle(service, playerRequest(player, "root", 1, 100L), estimate(12, 3), usage(12, 3, 0));

        var status = service.statusJson(player);

        assertEquals(9, status.size());
        assertEquals(15L, status.get("totalTokens").getAsLong());
        assertFalse(status.has("playerId"));
        assertNull(status.get("players"));
    }

    private static PersonalBudgetService service(Path root, java.util.function.LongSupplier limit) {
        PersonalBudgetService service = new PersonalBudgetService(limit);
        service.open(root.resolve("personal-budget.json"));
        return service;
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
