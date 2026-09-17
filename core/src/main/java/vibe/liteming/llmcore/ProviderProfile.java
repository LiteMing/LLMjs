// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

/**
 * Provider/model metadata kept separate from the legacy {@link ProviderSpec} ABI.
 *
 * @since 1.4.1
 */
public record ProviderProfile(String provider, ProviderCapabilities capabilities,
        LlmCostRate costRate, java.util.Map<String, LlmCostRate> modelCostRates) {
    /** Binary-compatible profile shape used before cost metadata was added. */
    public ProviderProfile(String provider, ProviderCapabilities capabilities) {
        this(provider, capabilities, LlmCostRate.DEFAULT, java.util.Map.of());
    }

    public ProviderProfile(String provider, ProviderCapabilities capabilities, LlmCostRate costRate) {
        this(provider, capabilities, costRate, java.util.Map.of());
    }

    public ProviderProfile {
        provider = provider == null ? "" : provider.trim();
        capabilities = capabilities == null ? ProviderCapabilities.textOnly() : capabilities;
        costRate = costRate == null ? LlmCostRate.DEFAULT : costRate;
        modelCostRates = modelCostRates == null ? java.util.Map.of() : java.util.Map.copyOf(modelCostRates);
    }

    public LlmCostRate costRateFor(String model) {
        return modelCostRates.getOrDefault(model == null ? "" : model, costRate);
    }

    public static ProviderProfile textOnly(String provider) {
        return new ProviderProfile(provider, ProviderCapabilities.textOnly(), LlmCostRate.DEFAULT, java.util.Map.of());
    }
}
