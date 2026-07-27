package vibe.liteming.llmcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
                return LlmRequestAccounting.Reservation.deny("personal budget exhausted");
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
        assertEquals(0, settled.get());
    }

    @Test
    void oldRequestConstructorLeavesBillingUnspecified() {
        LlmRequest legacy = LlmRequest.routed(List.of(),
                new LlmRequestContext("request", "CHAT", "", "", "", "", "", false));
        assertEquals(LlmBillingContext.PrincipalKind.UNSPECIFIED,
                legacy.billingContext().principalKind());
        assertFalse(legacy.billingContext().specified());
    }
}
