package vibe.liteming.llmjs.test;

import vibe.liteming.llmcore.LlmMessageFinalization;
import vibe.liteming.llmcore.LlmMessageFinalizer;
import vibe.liteming.llmcore.LlmBillingContext;
import vibe.liteming.llmcore.LlmCallBudget;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestContext;
import vibe.liteming.llmcore.LlmResolvedParameters;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Pure adapter that executes a validated Console request through llm-core. */
public final class ConsoleTestExecutor {
    private ConsoleTestExecutor() {
    }

    public static CompletableFuture<String> execute(LlmOrchestrator orchestrator,
            PriorityRoutingConfig routingConfig, ConsoleTestRequest test) {
        return execute(orchestrator, routingConfig, test, "");
    }

    public static CompletableFuture<String> execute(LlmOrchestrator orchestrator,
            PriorityRoutingConfig routingConfig, ConsoleTestRequest test, String principalId) {
        if (orchestrator == null) {
            return CompletableFuture.completedFuture(ConsoleTestCodec.error(test.requestId(), "LLM core unavailable"));
        }
        Map<String, String> metadata = test.metadata();
        LlmRequestContext context = new LlmRequestContext(test.requestId(), test.purpose(),
                metadata.getOrDefault("entityId", ""), metadata.getOrDefault("entityType", ""),
                metadata.getOrDefault("customName", ""), metadata.getOrDefault("sessionId", ""),
                test.routingMode().name(), Boolean.parseBoolean(metadata.getOrDefault("structured", "false")),
                metadata.getOrDefault("responderEntityId", metadata.getOrDefault("entityId", "")),
                metadata.getOrDefault("responderName", metadata.getOrDefault("customName", "")),
                metadata.getOrDefault("triggerSource", "console-test"),
                metadata.getOrDefault("audience", "operator"),
                metadata.getOrDefault("inputKind", test.generationType()));
        List<String> explicitChain = test.routingMode() == ConsoleTestRequest.RoutingMode.EXPLICIT_CHAIN
                ? test.providerChain() : List.of();
        LlmRequest template = new LlmRequest(List.of(), explicitChain, null, null, 0, context, test.overrides());
        List<String> resolvedChain = orchestrator.resolveChain(template);
        String budgetProvider = resolvedChain.isEmpty() ? "" : resolvedChain.get(0);
        LlmMessageFinalization finalization = orchestrator.finalizeDraft(test.toDraft(), template, budgetProvider,
                LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR);
        LlmRequest finalRequest = new LlmRequest(finalization.messages(), explicitChain, null, null, 0, context,
                test.overrides());
        LlmCallBudget worstCase = orchestrator.estimateWorstCaseBudget(finalRequest);
        String rootId = test.requestId() == null || test.requestId().isBlank()
                ? UUID.randomUUID().toString() : test.requestId();
        LlmBillingContext billing = principalId == null || principalId.isBlank()
                ? LlmBillingContext.system(LlmBillingContext.PrincipalKind.SCRIPT_SYSTEM, rootId,
                        Math.max(1, worstCase.maxCalls()), Math.max(1L, worstCase.maxTokens()))
                : LlmBillingContext.player(principalId, rootId,
                        Math.max(1, worstCase.maxCalls()), Math.max(1L, worstCase.maxTokens()));
        finalRequest = finalRequest.withBillingContext(billing);
        LlmRequest billedRequest = finalRequest;
        return orchestrator.send(billedRequest).thenApply(response -> {
            String actualProvider = response.provider().isBlank() ? budgetProvider : response.provider();
            LlmResolvedParameters effective = orchestrator.resolveParameters(billedRequest, actualProvider);
            return ConsoleTestCodec.result(test, response, finalization, effective,
                    RoutingConfigStore.fingerprint(routingConfig));
        });
    }
}
