package vibe.liteming.llmjs.test;

import vibe.liteming.llmcore.LlmMessageFinalization;
import vibe.liteming.llmcore.LlmMessageFinalizer;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestContext;
import vibe.liteming.llmcore.LlmResolvedParameters;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Pure adapter that executes a validated Console request through llm-core. */
public final class ConsoleTestExecutor {
    private ConsoleTestExecutor() {
    }

    public static CompletableFuture<String> execute(LlmOrchestrator orchestrator,
            PriorityRoutingConfig routingConfig, ConsoleTestRequest test) {
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
        return orchestrator.send(finalRequest).thenApply(response -> {
            String actualProvider = response.provider().isBlank() ? budgetProvider : response.provider();
            LlmResolvedParameters effective = orchestrator.resolveParameters(finalRequest, actualProvider);
            return ConsoleTestCodec.result(test, response, finalization, effective,
                    RoutingConfigStore.fingerprint(routingConfig));
        });
    }
}
