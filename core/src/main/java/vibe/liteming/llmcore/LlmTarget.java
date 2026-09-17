// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Stable identity for one enabled model exposed by a provider. */
public record LlmTarget(String provider, String model) {
    public LlmTarget {
        provider = clean(provider);
        model = clean(model);
        if (provider.isEmpty()) throw new IllegalArgumentException("provider name is blank");
    }

    public static LlmTarget of(String provider, String model) {
        return new LlmTarget(provider, model);
    }

    /**
     * Parse the persisted {@code provider/model} id. A provider-only value is
     * retained solely so the persistence owner can perform the 1.5.1 migration.
     */
    public static LlmTarget parse(String value) {
        String cleaned = clean(value);
        int separator = cleaned.indexOf('/');
        if (separator < 0) return new LlmTarget(decode(cleaned), "");
        String provider = decode(cleaned.substring(0, separator));
        String model = decode(cleaned.substring(separator + 1));
        if (model.isEmpty()) throw new IllegalArgumentException("model id is blank");
        return new LlmTarget(provider, model);
    }

    public boolean isProviderOnly() {
        return model.isEmpty();
    }

    /** Machine-stable id; typical values remain human-readable. */
    public String id() {
        return isProviderOnly() ? encode(provider) : encode(provider) + "/" + encode(model);
    }

    public String displayName() {
        return isProviderOnly() ? provider : provider + "/" + model;
    }

    private static String encode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':') {
                result.append((char) c);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xf, 16)));
                result.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return result.toString();
    }

    private static String decode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) throw new IllegalArgumentException("invalid target id escape");
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("invalid target id escape");
                bytes.write((high << 4) | low);
                index += 3;
            } else {
                byte[] encoded = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(encoded);
                index++;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
