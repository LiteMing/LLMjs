package vibe.liteming.llmcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRequestAccountingTest {
    @AfterEach
    void clearPolicy() {
        LlmRequestAccounting.clear();
    }

    @Test
    void deniedProviderAttemptNeverStartsHttpOrSettles() {
        AtomicInteger settled = new AtomicInteger();
        LlmRequestAccounting.install(new LlmRequestAccounting.Policy() {
            @Override
            public LlmRequestAccounting.Reservation reserve(
                    LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
                return LlmRequestAccounting.Reservation.deny(
                        LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED,
                        "personal budget exhausted");
            }

            @Override
            public void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation,
                    LlmRequestAccounting.AttemptUsage usage) {
                settled.incrementAndGet();
            }
        });
        ProviderSpec spec = new ProviderSpec("test", "openai", "http://127.0.0.1:9/chat",
                "model", 0.2, 32, List.of(new ProviderSpec.Credential("one", "key", 1)));
        LlmBillingContext billing = LlmBillingContext.player(
                "a78cc4bd-861b-45dc-87ec-4699aab476e5", "root", 1, 1_000L);
        LlmRequest request = LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                new LlmRequestContext("request", "CHAT", "", "", "", "", "", false,
                        "", "", "", "", "player"), billing);

        LlmResponse response = new LlmOrchestrator(Map.of("test", spec)).send(request).join();

        assertFalse(response.success());
        assertEquals("Billing denied: personal budget exhausted", response.error());
        assertEquals(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED, response.denyCode());
        assertEquals(0, settled.get());

        LlmResponse streamed = new LlmOrchestrator(Map.of("test", spec))
                .sendStreaming(request, ignored -> { }).join();
        assertFalse(streamed.success());
        assertEquals(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED, streamed.denyCode());
        assertEquals(0, settled.get());
    }

    @Test
    void missingPolicyFailsClosedForPlayerButKeepsExplicitSystemRequestsAvailable() {
        LlmRequestAccounting.clear();
        LlmRequest player = request(LlmBillingContext.player(
                "a78cc4bd-861b-45dc-87ec-4699aab476e5", "player-root", 1, 100L));
        LlmRequest system = request(LlmBillingContext.system(
                LlmBillingContext.PrincipalKind.SERVER_MAINTENANCE, "system-root", 1, 100L));

        LlmRequestAccounting.Reservation denied = LlmRequestAccounting.reserve(player,
                new LlmRequestAccounting.AttemptEstimate("test", 1, 1));

        assertFalse(denied.allowed());
        assertEquals(LlmRequestAccounting.DenyCode.POLICY_UNAVAILABLE, denied.denyCode());
        assertEquals(LlmRequestAccounting.DenyCode.POLICY_UNAVAILABLE,
                LlmRequestAccounting.preflight(player.billingContext()).denyCode());
        assertTrue(LlmRequestAccounting.reserve(system,
                new LlmRequestAccounting.AttemptEstimate("test", 1, 1)).allowed());
        assertFalse(LlmRequestAccounting.isInstalled());
    }

    @Test
    void oldRequestConstructorLeavesBillingUnspecified() {
        LlmRequest legacy = LlmRequest.routed(List.of(),
                new LlmRequestContext("request", "CHAT", "", "", "", "", "", false));
        assertEquals(LlmBillingContext.PrincipalKind.UNSPECIFIED,
                legacy.billingContext().principalKind());
        assertFalse(legacy.billingContext().specified());
    }

    @Test
    void frozenAccountingAndResponseConstructorsRemainLinkable() throws Exception {
        assertTrue(LlmRequestAccounting.Reservation.class.getConstructor(boolean.class, String.class,
                String.class, long.class) != null);
        assertTrue(LlmResponse.class.getConstructor(boolean.class, String.class, String.class,
                String.class, String.class, String.class, int.class, int.class, long.class,
                List.class, String.class, String.class, String.class) != null);
    }

    private static LlmRequest request(LlmBillingContext billing) {
        return LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                new LlmRequestContext("request", "CHAT", "", "", "", "", "", false,
                        "", "", "", "", "player"), billing);
    }
}
