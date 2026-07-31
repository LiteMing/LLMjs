// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Atomic persistence for the fail-closed optional-capability policy.
 *
 * @since 1.4.1
 */
public final class CapabilityPolicyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "purposes");
    private static final Set<String> PURPOSE_FIELDS = Set.of("webSearch");

    private CapabilityPolicyStore() {
    }

    public static LlmCapabilityPolicy load(Path policyFile) {
        return load(policyFile, ignored -> { });
    }

    /** Load fail-closed and report malformed/unreadable state to the supplied sink. */
    public static LlmCapabilityPolicy load(Path policyFile, Consumer<String> diagnosticSink) {
        Consumer<String> sink = diagnosticSink == null ? ignored -> { } : diagnosticSink;
        if (policyFile == null || !Files.exists(policyFile)) return LlmCapabilityPolicy.empty();
        try {
            String content = Files.readString(policyFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) throw new IllegalArgumentException("capability policy file is empty");
            return parse(content);
        } catch (Exception e) {
            sink.accept(rootMessage(e));
            return LlmCapabilityPolicy.empty();
        }
    }

    /** Parse a complete schema-1 snapshot, rejecting unknown or ambiguous fields. */
    public static LlmCapabilityPolicy parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("capability policy JSON is empty");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("capability policy JSON is malformed: " + rootMessage(e), e);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("capability policy root must be an object");
        JsonObject root = parsed.getAsJsonObject();
        rejectUnknownFields(root, ROOT_FIELDS, "capability policy");
        if (!root.has("schemaVersion") || !isInteger(root.get("schemaVersion"))
                || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("capability policy schemaVersion must be " + SCHEMA_VERSION);
        }
        if (!root.has("purposes") || !root.get("purposes").isJsonObject()) {
            throw new IllegalArgumentException("capability policy purposes must be an object");
        }

        LinkedHashSet<String> webSearchPurposes = new LinkedHashSet<>();
        for (var entry : root.getAsJsonObject("purposes").entrySet()) {
            String purpose = entry.getKey() == null ? "" : entry.getKey().trim();
            if (purpose.isEmpty()) throw new IllegalArgumentException("purpose id must not be blank");
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("purposes." + purpose + " must be an object");
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            rejectUnknownFields(value, PURPOSE_FIELDS, "purposes." + purpose);
            if (!value.has("webSearch") || !value.get("webSearch").isJsonPrimitive()
                    || !value.getAsJsonPrimitive("webSearch").isBoolean()) {
                throw new IllegalArgumentException("purposes." + purpose + ".webSearch must be a boolean");
            }
            if (value.get("webSearch").getAsBoolean()) webSearchPurposes.add(purpose);
        }
        return LlmCapabilityPolicy.allowingWebSearch(webSearchPurposes);
    }

    public static synchronized boolean save(Path policyFile, LlmCapabilityPolicy policy) {
        if (policyFile == null) return false;
        try {
            Files.createDirectories(policyFile.toAbsolutePath().normalize().getParent());
            Path temp = policyFile.resolveSibling(policyFile.getFileName() + ".tmp");
            Files.writeString(temp, toJsonString(policy), StandardCharsets.UTF_8);
            try {
                Files.move(temp, policyFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, policyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String toJsonString(LlmCapabilityPolicy policy) {
        return GSON.toJson(toJson(policy));
    }

    public static String fingerprint(LlmCapabilityPolicy policy) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toJsonString(policy).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JsonObject toJson(LlmCapabilityPolicy policy) {
        LlmCapabilityPolicy safe = policy == null ? LlmCapabilityPolicy.empty() : policy;
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject purposes = new JsonObject();
        for (String purpose : safe.webSearchPurposes()) {
            JsonObject item = new JsonObject();
            item.addProperty("webSearch", true);
            purposes.add(purpose, item);
        }
        root.add("purposes", purposes);
        return root;
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String path) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " contains unknown field " + field);
            }
        }
    }

    private static boolean isInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        double number = value.getAsDouble();
        return Double.isFinite(number) && number == Math.rint(number)
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
