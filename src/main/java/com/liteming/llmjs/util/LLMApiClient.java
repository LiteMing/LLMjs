package com.liteming.llmjs.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liteming.llmjs.LLMjs;
import com.liteming.llmjs.config.LLMConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LLMApiClient {
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static class Message {
        public String role;
        public String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", role);
            obj.addProperty("content", content);
            return obj;
        }
    }

    public static class ChatRequest {
        public String model;
        public List<Message> messages;
        public Double temperature;
        public Integer maxTokens;

        public ChatRequest(String model, List<Message> messages) {
            this.model = model;
            this.messages = messages;
        }

        public ChatRequest temperature(double temp) {
            this.temperature = temp;
            return this;
        }

        public ChatRequest maxTokens(int tokens) {
            this.maxTokens = tokens;
            return this;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("model", model);

            JsonArray messagesArray = new JsonArray();
            for (Message msg : messages) {
                messagesArray.add(msg.toJson());
            }
            obj.add("messages", messagesArray);

            if (temperature != null) {
                obj.addProperty("temperature", temperature);
            }
            if (maxTokens != null) {
                obj.addProperty("max_tokens", maxTokens);
            }

            return obj;
        }
    }

    public static class ChatResponse {
        public boolean success;
        public String content;
        public String error;
        public String model;
        public int promptTokens;
        public int completionTokens;
        public int totalTokens;

        public ChatResponse(boolean success) {
            this.success = success;
        }

        public static ChatResponse success(String content, String model, int promptTokens, int completionTokens) {
            ChatResponse response = new ChatResponse(true);
            response.content = content;
            response.model = model;
            response.promptTokens = promptTokens;
            response.completionTokens = completionTokens;
            response.totalTokens = promptTokens + completionTokens;
            return response;
        }

        public static ChatResponse error(String error) {
            ChatResponse response = new ChatResponse(false);
            response.error = error;
            return response;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("success", success);
            if (success) {
                obj.addProperty("content", content);
                obj.addProperty("model", model);
                obj.addProperty("prompt_tokens", promptTokens);
                obj.addProperty("completion_tokens", completionTokens);
                obj.addProperty("total_tokens", totalTokens);
            } else {
                obj.addProperty("error", error);
            }
            return obj;
        }
    }

    public static CompletableFuture<ChatResponse> chatAsync(@NotNull ChatRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return chat(request);
            } catch (Exception e) {
                LLMjs.LOGGER.error("Async chat request failed", e);
                return ChatResponse.error("Async request failed: " + e.getMessage());
            }
        });
    }

    public static ChatResponse chat(@NotNull ChatRequest request) {
        try {
            String url = LLMConfig.API_URL.get();
            String apiKey = LLMConfig.API_KEY.get();

            if (apiKey.equals("your-api-key-here") || apiKey.isEmpty()) {
                return ChatResponse.error("API key not configured");
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(LLMConfig.TIMEOUT.get()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(request.toJson().toString()))
                    .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ChatResponse.error("HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();

            if (responseJson.has("error")) {
                JsonObject error = responseJson.getAsJsonObject("error");
                return ChatResponse.error(error.get("message").getAsString());
            }

            JsonArray choices = responseJson.getAsJsonArray("choices");
            if (choices.size() == 0) {
                return ChatResponse.error("No choices in response");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            String content = message.get("content").getAsString();

            String model = responseJson.get("model").getAsString();

            int promptTokens = 0;
            int completionTokens = 0;
            if (responseJson.has("usage")) {
                JsonObject usage = responseJson.getAsJsonObject("usage");
                promptTokens = usage.get("prompt_tokens").getAsInt();
                completionTokens = usage.get("completion_tokens").getAsInt();
            }

            return ChatResponse.success(content, model, promptTokens, completionTokens);

        } catch (IOException | InterruptedException e) {
            LLMjs.LOGGER.error("Failed to send chat request", e);
            return ChatResponse.error("Request failed: " + e.getMessage());
        } catch (Exception e) {
            LLMjs.LOGGER.error("Unexpected error in chat request", e);
            return ChatResponse.error("Unexpected error: " + e.getMessage());
        }
    }

    public static String simpleChat(@NotNull String prompt) {
        return simpleChat(prompt, null);
    }

    public static String simpleChat(@NotNull String prompt, @Nullable String systemPrompt) {
        String model = LLMConfig.MODEL.get();

        java.util.List<Message> messages = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new Message("system", systemPrompt));
        }
        messages.add(new Message("user", prompt));

        ChatRequest request = new ChatRequest(model, messages)
                .temperature(LLMConfig.TEMPERATURE.get())
                .maxTokens(LLMConfig.MAX_TOKENS.get());

        ChatResponse response = chat(request);

        if (response.success) {
            return response.content;
        } else {
            return "Error: " + response.error;
        }
    }
}