package com.liteming.llmjs.http;

import com.liteming.llmjs.LLMjs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpService {
    public static final HttpService INSTANCE = new HttpService();

    private final HttpClient client;

    private HttpService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record HttpResult(int statusCode, String body, long latencyMs) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public CompletableFuture<HttpResult> postAsync(String url, String jsonBody,
                                                     Map<String, String> headers,
                                                     int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        headers.forEach(builder::header);

        HttpRequest request = builder.build();
        long startTime = System.currentTimeMillis();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    long latency = System.currentTimeMillis() - startTime;
                    return new HttpResult(response.statusCode(), response.body(), latency);
                })
                .exceptionally(ex -> {
                    long latency = System.currentTimeMillis() - startTime;
                    LLMjs.LOGGER.error("HTTP request failed: {}", url, ex);
                    return new HttpResult(-1, "Request failed: " + ex.getMessage(), latency);
                });
    }
}
