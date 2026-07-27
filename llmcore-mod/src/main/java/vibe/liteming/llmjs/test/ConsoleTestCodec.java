package vibe.liteming.llmjs.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmcore.LlmMessage;
import vibe.liteming.llmcore.LlmMessageFinalization;
import vibe.liteming.llmcore.LlmResolvedParameters;
import vibe.liteming.llmcore.LlmResponse;
import vibe.liteming.llmcore.PurposeRegistry;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict schema codec shared by client handoff, network packets and server execution. */
public final class ConsoleTestCodec {
    public static final int MAX_REQUEST_JSON_CHARS = 240_000;
    public static final int MAX_RESULT_JSON_CHARS = 900_000;
    private static final int MAX_BODY_CHARS = 250_000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ConsoleTestCodec() {
    }

    public static ConsoleTestRequest parseRequest(String json, boolean requireRegisteredPurpose) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("test request is empty");
        if (json.length() > MAX_REQUEST_JSON_CHARS) throw new IllegalArgumentException("test request exceeds limit");
        ConsoleTestRequest request;
        try {
            JsonObject raw = JsonParser.parseString(json).getAsJsonObject();
            if (raw.has("routingMode")) {
                String mode = raw.get("routingMode").getAsString();
                try {
                    ConsoleTestRequest.RoutingMode.valueOf(mode);
                } catch (Exception e) {
                    throw new IllegalArgumentException("unknown routingMode: " + mode);
                }
            }
            request = GSON.fromJson(json, ConsoleTestRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("test request JSON is malformed: " + rootMessage(e), e);
        }
        validate(request, requireRegisteredPurpose);
        return request;
    }

    public static String toJson(ConsoleTestRequest request) {
        validate(request, false);
        String json = GSON.toJson(request);
        if (json.length() > MAX_REQUEST_JSON_CHARS) throw new IllegalArgumentException("test request exceeds limit");
        return json;
    }

    public static String error(String requestId, String error) {
        JsonObject result = baseResult(requestId);
        result.addProperty("success", false);
        result.addProperty("error", error == null ? "Unknown test error" : error);
        return GSON.toJson(result);
    }

    public static String result(ConsoleTestRequest request, LlmResponse response,
            LlmMessageFinalization finalization, LlmResolvedParameters effective, String routingFingerprint) {
        JsonObject result = baseResult(request.requestId());
        result.addProperty("success", response.success());
        result.addProperty("purpose", request.purpose());
        result.addProperty("generationType", request.generationType());
        result.addProperty("routingMode", request.routingMode().name());
        result.addProperty("routingFingerprint", routingFingerprint == null ? "" : routingFingerprint);
        if (response.success()) result.addProperty("content", trim(response.content()));
        else result.addProperty("error", response.error());
        result.addProperty("provider", response.provider());
        result.addProperty("model", response.model());
        result.addProperty("finishReason", response.finishReason());
        result.addProperty("promptTokens", response.promptTokens());
        result.addProperty("completionTokens", response.completionTokens());
        result.addProperty("reasoningTokens", extractReasoningTokens(response.responseBody()));
        result.addProperty("latencyMs", response.latencyMs());
        result.add("effective", effectiveJson(effective));
        result.add("attempts", attemptsJson(response));
        result.add("finalMessages", messagesJson(finalization));
        result.add("budgetDecisions", decisionsJson(finalization));
        result.addProperty("hardBudgetTokens", finalization.hardBudgetTokens());
        result.addProperty("estimatedInputTokens", finalization.estimatedInputTokens());
        result.addProperty("withinBudget", finalization.withinBudget());
        result.addProperty("requestBody", trim(response.requestBody()));
        result.addProperty("responseBody", trim(response.responseBody()));
        String json = GSON.toJson(result);
        if (json.length() > MAX_RESULT_JSON_CHARS) {
            result.addProperty("requestBody", "[omitted: result exceeds wire limit]");
            result.addProperty("responseBody", "[omitted: result exceeds wire limit]");
            json = GSON.toJson(result);
        }
        return json;
    }

    public static String pretty(String json) {
        return PRETTY_GSON.toJson(JsonParser.parseString(json));
    }

    private static void validate(ConsoleTestRequest request, boolean requireRegisteredPurpose) {
        if (request == null) throw new IllegalArgumentException("test request is null");
        if (request.schemaVersion() != ConsoleTestRequest.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported test schemaVersion: " + request.schemaVersion());
        }
        try {
            UUID.fromString(request.requestId());
        } catch (Exception e) {
            throw new IllegalArgumentException("requestId must be a UUID");
        }
        if (request.routingMode() == null) throw new IllegalArgumentException("routingMode is required");
        if (request.purpose().isBlank()) throw new IllegalArgumentException("purpose is required");
        if (requireRegisteredPurpose && !PurposeRegistry.isRegistered(request.purpose())) {
            throw new IllegalArgumentException("unknown purpose: " + request.purpose());
        }
        if (request.providerChain().size() > ConsoleTestRequest.MAX_PROVIDER_CHAIN) {
            throw new IllegalArgumentException("providerChain exceeds limit");
        }
        if (request.routingMode() == ConsoleTestRequest.RoutingMode.EXPLICIT_CHAIN
                && request.providerChain().isEmpty()) {
            throw new IllegalArgumentException("explicit routing requires providerChain");
        }
        Set<String> providers = new HashSet<>();
        for (String provider : request.providerChain()) {
            if (provider.isBlank()) throw new IllegalArgumentException("provider name is blank");
            if (!providers.add(provider)) throw new IllegalArgumentException("duplicate provider: " + provider);
        }
        if (request.messages().isEmpty()) throw new IllegalArgumentException("messages are required");
        if (request.messages().size() > ConsoleTestRequest.MAX_MESSAGES) {
            throw new IllegalArgumentException("messages exceeds limit");
        }
        long contentChars = 0;
        for (int index = 0; index < request.messages().size(); index++) {
            ConsoleTestRequest.MessageEntry message = request.messages().get(index);
            if (message == null) throw new IllegalArgumentException("messages[" + index + "] is null");
            if (!Set.of("system", "user", "assistant").contains(message.role())) {
                throw new IllegalArgumentException("messages[" + index + "].role is invalid");
            }
            if (message.priority() < 0 || message.priority() > 1000) {
                throw new IllegalArgumentException("messages[" + index + "].priority must be 0..1000");
            }
            if (message.parts().isEmpty() || message.parts().size() > ConsoleTestRequest.MAX_PARTS_PER_MESSAGE) {
                throw new IllegalArgumentException("messages[" + index + "].parts count is invalid");
            }
            for (ConsoleTestRequest.Part part : message.parts()) {
                if (part == null || !("text".equals(part.type()) || "image".equals(part.type()))) {
                    throw new IllegalArgumentException("messages[" + index + "] has invalid part type");
                }
                if ("image".equals(part.type()) && (part.mimeType().isBlank() || part.base64Data().isBlank())) {
                    throw new IllegalArgumentException("messages[" + index + "] has incomplete image part");
                }
                contentChars += part.text().length() + part.base64Data().length();
            }
        }
        if (contentChars > ConsoleTestRequest.MAX_TOTAL_CONTENT_CHARS) {
            throw new IllegalArgumentException("message content exceeds limit");
        }
        if (request.metadata().size() > ConsoleTestRequest.MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("metadata exceeds limit");
        }
    }

    private static JsonObject baseResult(String requestId) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", ConsoleTestRequest.SCHEMA_VERSION);
        result.addProperty("requestId", requestId == null ? "" : requestId);
        return result;
    }

    private static JsonObject effectiveJson(LlmResolvedParameters effective) {
        JsonObject json = new JsonObject();
        if (effective == null) return json;
        json.addProperty("provider", effective.provider());
        if (effective.temperature() != null) json.addProperty("temperature", effective.temperature());
        if (effective.maxOutputTokens() != null) json.addProperty("maxOutputTokens", effective.maxOutputTokens());
        json.addProperty("timeoutSeconds", effective.timeoutSeconds());
        if (effective.hasBoundedInput()) json.addProperty("inputBudgetTokens", effective.inputBudgetTokens());
        else json.addProperty("inputBudgetUnbounded", true);
        json.addProperty("outputReserveTokens", effective.outputReserveTokens());
        if (effective.contextWindowTokens() != null) {
            json.addProperty("contextWindowTokens", effective.contextWindowTokens());
        }
        return json;
    }

    private static JsonArray attemptsJson(LlmResponse response) {
        JsonArray attempts = new JsonArray();
        for (LlmResponse.Attempt attempt : response.attempts()) {
            JsonObject json = new JsonObject();
            json.addProperty("provider", attempt.provider());
            json.addProperty("credentialId", attempt.credentialId());
            json.addProperty("success", attempt.success());
            json.addProperty("error", attempt.error());
            json.addProperty("latencyMs", attempt.latencyMs());
            json.addProperty("finishReason", attempt.finishReason());
            attempts.add(json);
        }
        return attempts;
    }

    private static JsonArray messagesJson(LlmMessageFinalization finalization) {
        JsonArray messages = new JsonArray();
        for (LlmMessage message : finalization.messages()) {
            JsonObject json = new JsonObject();
            json.addProperty("role", message.role());
            JsonArray parts = new JsonArray();
            for (LlmMessage.Part part : message.parts()) {
                JsonObject partJson = new JsonObject();
                if (part instanceof LlmMessage.ImagePart image) {
                    partJson.addProperty("type", "image");
                    partJson.addProperty("mimeType", image.mimeType());
                    partJson.addProperty("detail", image.detail());
                    partJson.addProperty("base64Chars", image.base64Data().length());
                } else {
                    partJson.addProperty("type", "text");
                    partJson.addProperty("text", part.asText());
                }
                parts.add(partJson);
            }
            json.add("parts", parts);
            messages.add(json);
        }
        return messages;
    }

    private static JsonArray decisionsJson(LlmMessageFinalization finalization) {
        JsonArray decisions = new JsonArray();
        for (LlmMessageFinalization.Decision decision : finalization.decisions()) {
            JsonObject json = new JsonObject();
            json.addProperty("index", decision.index());
            json.addProperty("entryId", decision.entryId());
            json.addProperty("provenance", decision.provenance());
            json.addProperty("included", decision.included());
            json.addProperty("reason", decision.reason());
            json.addProperty("estimatedTokens", decision.estimatedTokens());
            json.addProperty("required", decision.required());
            json.addProperty("priority", decision.priority());
            decisions.add(json);
        }
        return decisions;
    }

    private static int extractReasoningTokens(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return 0;
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usageMetadata");
                if (usage.has("thoughtsTokenCount")) return usage.get("thoughtsTokenCount").getAsInt();
            }
            if (!root.has("usage") || !root.get("usage").isJsonObject()) return 0;
            JsonObject usage = root.getAsJsonObject("usage");
            if (usage.has("reasoning_tokens")) return usage.get("reasoning_tokens").getAsInt();
            if (usage.has("completion_tokens_details")
                    && usage.get("completion_tokens_details").isJsonObject()) {
                JsonObject details = usage.getAsJsonObject("completion_tokens_details");
                if (details.has("reasoning_tokens")) return details.get("reasoning_tokens").getAsInt();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String trim(String value) {
        if (value == null) return "";
        return value.length() <= MAX_BODY_CHARS ? value : value.substring(0, MAX_BODY_CHARS) + "...[truncated]";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
