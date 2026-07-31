// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Built-in hosted-search wire dialects, intentionally separate from base chat formats. */
final class HostedWebSearchAdapters {
    private static final int MAX_SOURCES = 64;
    private static final Map<String, HostedWebSearchAdapter> ADAPTERS = Map.of(
            HostedWebSearchAdapterIds.OPENAI_CHAT, new OpenAiChatAdapter(),
            HostedWebSearchAdapterIds.ANTHROPIC_MESSAGES, new AnthropicMessagesAdapter(),
            HostedWebSearchAdapterIds.GEMINI_GENERATE_CONTENT, new GeminiGenerateContentAdapter(),
            HostedWebSearchAdapterIds.DASHSCOPE_OPENAI_CHAT, new DashScopeChatAdapter());

    private HostedWebSearchAdapters() {
    }

    static HostedWebSearchAdapter find(String id) {
        if (id == null) return null;
        return ADAPTERS.get(id.trim().toLowerCase(Locale.ROOT));
    }

    static java.util.Set<String> ids() {
        return ADAPTERS.keySet();
    }

    interface HostedWebSearchAdapter {
        boolean supportsFormat(String format);

        void apply(JsonObject requestBody);

        Evidence parse(JsonObject responseBody, String provider);
    }

    record Evidence(int uses, List<LlmSource> sources) {
        Evidence {
            uses = Math.max(0, uses);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        boolean used() {
            return uses > 0;
        }

        static Evidence none() {
            return new Evidence(0, List.of());
        }
    }

    private static final class OpenAiChatAdapter implements HostedWebSearchAdapter {
        @Override
        public boolean supportsFormat(String format) {
            return "openai".equals(format);
        }

        @Override
        public void apply(JsonObject requestBody) {
            requestBody.add("web_search_options", new JsonObject());
        }

        @Override
        public Evidence parse(JsonObject root, String provider) {
            JsonObject message = firstChoiceMessage(root);
            JsonArray annotations = array(message, "annotations");
            List<LlmSource> sources = new ArrayList<>();
            if (annotations != null) {
                for (JsonElement element : annotations) {
                    if (sources.size() >= MAX_SOURCES) break;
                    if (!element.isJsonObject()) continue;
                    JsonObject annotation = element.getAsJsonObject();
                    JsonObject citation = object(annotation, "url_citation");
                    if (citation == null) continue;
                    sources.add(new LlmSource(string(citation, "url"), string(citation, "title"), "",
                            provider, "url_citation", integer(citation, "start_index"),
                            integer(citation, "end_index"), Map.of()));
                }
            }
            // Chat Completions exposes URL annotations, not a separate search-call item.
            return new Evidence(sources.isEmpty() ? 0 : 1, sources);
        }
    }

    private static final class AnthropicMessagesAdapter implements HostedWebSearchAdapter {
        @Override
        public boolean supportsFormat(String format) {
            return "claude".equals(format) || "anthropic".equals(format);
        }

        @Override
        public void apply(JsonObject requestBody) {
            JsonArray tools = new JsonArray();
            JsonObject search = new JsonObject();
            search.addProperty("type", "web_search_20250305");
            search.addProperty("name", "web_search");
            tools.add(search);
            requestBody.add("tools", tools);
        }

        @Override
        public Evidence parse(JsonObject root, String provider) {
            JsonArray content = array(root, "content");
            if (content == null) return Evidence.none();
            int uses = 0;
            List<LlmSource> sources = new ArrayList<>();
            for (JsonElement element : content) {
                if (!element.isJsonObject()) continue;
                JsonObject block = element.getAsJsonObject();
                String type = string(block, "type");
                if ("server_tool_use".equals(type) && "web_search".equals(string(block, "name"))) {
                    uses++;
                }
                if ("web_search_tool_result".equals(type)) {
                    JsonArray results = array(block, "content");
                    if (results != null) {
                        for (JsonElement resultElement : results) {
                            if (sources.size() >= MAX_SOURCES) break;
                            if (!resultElement.isJsonObject()) continue;
                            JsonObject result = resultElement.getAsJsonObject();
                            if (!"web_search_result".equals(string(result, "type"))) continue;
                            sources.add(new LlmSource(string(result, "url"), string(result, "title"),
                                    string(result, "page_age"), provider, "web_search_result"));
                        }
                    }
                }
                JsonArray citations = array(block, "citations");
                if (citations != null) {
                    for (JsonElement citationElement : citations) {
                        if (sources.size() >= MAX_SOURCES) break;
                        if (!citationElement.isJsonObject()) continue;
                        JsonObject citation = citationElement.getAsJsonObject();
                        sources.add(new LlmSource(string(citation, "url"), string(citation, "title"),
                                string(citation, "cited_text"), provider, string(citation, "type")));
                    }
                }
            }
            return new Evidence(uses, deduplicate(sources));
        }
    }

    private static final class GeminiGenerateContentAdapter implements HostedWebSearchAdapter {
        @Override
        public boolean supportsFormat(String format) {
            return "gemini".equals(format);
        }

        @Override
        public void apply(JsonObject requestBody) {
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            tool.add("googleSearch", new JsonObject());
            tools.add(tool);
            requestBody.add("tools", tools);
        }

        @Override
        public Evidence parse(JsonObject root, String provider) {
            JsonObject candidate = firstObject(array(root, "candidates"));
            JsonObject metadata = object(candidate, "groundingMetadata");
            if (metadata == null) metadata = object(candidate, "grounding_metadata");
            if (metadata == null) return Evidence.none();
            JsonArray queries = array(metadata, "webSearchQueries");
            if (queries == null) queries = array(metadata, "web_search_queries");
            JsonArray chunks = array(metadata, "groundingChunks");
            if (chunks == null) chunks = array(metadata, "grounding_chunks");
            List<LlmSource> sources = new ArrayList<>();
            if (chunks != null) {
                for (JsonElement element : chunks) {
                    if (sources.size() >= MAX_SOURCES) break;
                    if (!element.isJsonObject()) continue;
                    JsonObject web = object(element.getAsJsonObject(), "web");
                    if (web == null) continue;
                    sources.add(new LlmSource(string(web, "uri"), string(web, "title"), "", provider,
                            "grounding_chunk"));
                }
            }
            int uses = queries == null ? 0 : queries.size();
            if (uses == 0 && !sources.isEmpty()) uses = 1;
            return new Evidence(uses, sources);
        }
    }

    private static final class DashScopeChatAdapter implements HostedWebSearchAdapter {
        @Override
        public boolean supportsFormat(String format) {
            return "openai".equals(format);
        }

        @Override
        public void apply(JsonObject requestBody) {
            requestBody.addProperty("enable_search", true);
            JsonObject options = new JsonObject();
            options.addProperty("enable_source", true);
            requestBody.add("search_options", options);
        }

        @Override
        public Evidence parse(JsonObject root, String provider) {
            JsonObject searchInfo = object(root, "search_info");
            JsonObject output = object(root, "output");
            if (searchInfo == null) searchInfo = object(output, "search_info");
            List<LlmSource> sources = new ArrayList<>();
            JsonArray results = array(searchInfo, "search_results");
            if (results != null) {
                for (JsonElement element : results) {
                    if (sources.size() >= MAX_SOURCES) break;
                    if (!element.isJsonObject()) continue;
                    JsonObject result = element.getAsJsonObject();
                    Map<String, String> metadata = new LinkedHashMap<>();
                    String site = string(result, "site_name");
                    String index = string(result, "index");
                    if (!site.isEmpty()) metadata.put("siteName", site);
                    if (!index.isEmpty()) metadata.put("index", index);
                    sources.add(new LlmSource(string(result, "url"), string(result, "title"), "", provider,
                            "search_result", null, null, metadata));
                }
            }
            int uses = 0;
            JsonObject usage = object(root, "usage");
            JsonObject plugins = object(usage, "plugins");
            JsonObject search = object(plugins, "search");
            Integer count = integer(search, "count");
            if (count != null) uses = Math.max(0, count);
            if (uses == 0 && !sources.isEmpty()) uses = 1;
            return new Evidence(uses, sources);
        }
    }

    private static JsonObject firstChoiceMessage(JsonObject root) {
        JsonObject choice = firstObject(array(root, "choices"));
        return object(choice, "message");
    }

    private static JsonObject firstObject(JsonArray array) {
        return array != null && !array.isEmpty() && array.get(0).isJsonObject()
                ? array.get(0).getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject owner, String key) {
        return owner != null && owner.has(key) && owner.get(key).isJsonObject()
                ? owner.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject owner, String key) {
        return owner != null && owner.has(key) && owner.get(key).isJsonArray()
                ? owner.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject owner, String key) {
        if (owner == null || !owner.has(key) || owner.get(key).isJsonNull()
                || !owner.get(key).isJsonPrimitive()) return "";
        try {
            return owner.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Integer integer(JsonObject owner, String key) {
        if (owner == null || !owner.has(key) || owner.get(key).isJsonNull()) return null;
        try {
            return owner.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<LlmSource> deduplicate(List<LlmSource> sources) {
        LinkedHashMap<String, LlmSource> unique = new LinkedHashMap<>();
        for (LlmSource source : sources) {
            String key = source.uri().isEmpty() ? source.title() + '\u0000' + source.snippet() : source.uri();
            unique.putIfAbsent(key, source);
        }
        return List.copyOf(unique.values());
    }
}
