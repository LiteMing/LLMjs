// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

/**
 * Provider/model metadata kept separate from the legacy {@link ProviderSpec} ABI.
 *
 * @since 1.4.1
 */
public record ProviderProfile(String provider, ProviderCapabilities capabilities) {
    public ProviderProfile {
        provider = provider == null ? "" : provider.trim();
        capabilities = capabilities == null ? ProviderCapabilities.textOnly() : capabilities;
    }

    public static ProviderProfile textOnly(String provider) {
        return new ProviderProfile(provider, ProviderCapabilities.textOnly());
    }
}
