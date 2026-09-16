package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRequestLoggerTest {
    @Test
    void publishesEachTransportAttemptToDedicatedListeners() {
        AtomicReference<LlmRequestLogger.AttemptEvent> captured = new AtomicReference<>();
        Consumer<LlmRequestLogger.AttemptEvent> listener = captured::set;
        LlmRequestLogger.addAttemptListener(listener);
        try {
            LlmRequestLogger.publishAttempt(new LlmRequestLogger.AttemptEvent(
                    "CHAT", "request", "deepseek", "deepseek-chat", "deepseek#2",
                    true, 321L, "", "stop"));
            assertEquals("deepseek", captured.get().provider());
            assertEquals("deepseek#2", captured.get().credentialId());
            assertEquals(321L, captured.get().latencyMs());
        } finally {
            LlmRequestLogger.removeAttemptListener(listener);
        }
    }

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
                            "", "", "player", "PLAYER:alex", "operator", "chat"), billing);
            LlmResponse response = new LlmResponse(true, "answer", "", "provider", "model", "credential",
                    4, 2, 1L, List.of());

            LlmRequestLogger.publish("test", request, response);

            assertEquals("PLAYER", captured.get().billingPrincipal());
            assertEquals("a78cc4bd-861b-45dc-87ec-4699aab476e5", captured.get().billingPrincipalId());
            assertEquals("root-request", captured.get().causalRootRequestId());
            assertEquals("player", captured.get().triggerSource());
            assertEquals("PLAYER:alex", captured.get().addressee());
            assertEquals("operator", captured.get().audience());
        } finally {
            LlmRequestLogger.removeListener(listener);
        }
    }
}
