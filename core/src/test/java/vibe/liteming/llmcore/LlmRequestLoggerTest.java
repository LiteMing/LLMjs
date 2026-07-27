package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRequestLoggerTest {
    @Test
    void publishesExplicitPrincipalForAuditConsumers() {
        AtomicReference<LlmRequestLogger.Event> captured = new AtomicReference<>();
        Consumer<LlmRequestLogger.Event> listener = captured::set;
        LlmRequestLogger.addListener(listener);
        try {
            LlmBillingContext billing = LlmBillingContext.player(
                    "a78cc4bd-861b-45dc-87ec-4699aab476e5", "root-request", 1, 100L);
            LlmRequest request = LlmRequest.routed(List.of(new LlmMessage("user", "hello")),
                    new LlmRequestContext("request", "CHAT", "", "", "", "", "", false,
                            "", "", "player", "operator", "chat"), billing);
            LlmResponse response = new LlmResponse(true, "answer", "", "provider", "model", "credential",
                    4, 2, 1L, List.of());

            LlmRequestLogger.publish("test", request, response);

            assertEquals("PLAYER", captured.get().billingPrincipal());
            assertEquals("a78cc4bd-861b-45dc-87ec-4699aab476e5", captured.get().billingPrincipalId());
            assertEquals("root-request", captured.get().causalRootRequestId());
        } finally {
            LlmRequestLogger.removeListener(listener);
        }
    }
}
