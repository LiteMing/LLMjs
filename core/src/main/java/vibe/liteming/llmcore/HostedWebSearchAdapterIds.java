package vibe.liteming.llmcore;

import java.util.Set;

/**
 * Stable ids accepted by {@code capabilities.webSearch.adapter}.
 *
 * @since 1.4.1
 */
public final class HostedWebSearchAdapterIds {
    public static final String OPENAI_CHAT = "openai_chat_web_search";
    public static final String ANTHROPIC_MESSAGES = "anthropic_messages_web_search";
    public static final String GEMINI_GENERATE_CONTENT = "gemini_generate_content_google_search";
    public static final String DASHSCOPE_OPENAI_CHAT = "dashscope_openai_chat_web_search";

    private static final Set<String> BUILT_IN = Set.of(
            OPENAI_CHAT, ANTHROPIC_MESSAGES, GEMINI_GENERATE_CONTENT, DASHSCOPE_OPENAI_CHAT);

    private HostedWebSearchAdapterIds() {
    }

    public static Set<String> builtIn() {
        return BUILT_IN;
    }
}
