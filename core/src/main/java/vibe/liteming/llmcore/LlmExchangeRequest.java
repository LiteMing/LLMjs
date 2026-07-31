// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.util.List;

/**
 * Additive typed request surface. Legacy {@link LlmOrchestrator#send(LlmRequest)}
 * never infers or activates hosted tools.
 *
 * @since 1.4.1
 */
public record LlmExchangeRequest(
        LlmRequest request,
        LlmHostedWebSearchRequest webSearch) {

    public LlmExchangeRequest {
        request = request == null
                ? LlmRequest.routed(List.of(), LlmRequestContext.chat())
                : request;
        webSearch = webSearch == null ? LlmHostedWebSearchRequest.disabled() : webSearch;
    }

    public static LlmExchangeRequest text(LlmRequest request) {
        return new LlmExchangeRequest(request, LlmHostedWebSearchRequest.disabled());
    }

    public static LlmExchangeRequest withWebSearch(LlmRequest request,
            LlmHostedWebSearchRequest.Requirement requirement) {
        return new LlmExchangeRequest(request, new LlmHostedWebSearchRequest(requirement));
    }
}
