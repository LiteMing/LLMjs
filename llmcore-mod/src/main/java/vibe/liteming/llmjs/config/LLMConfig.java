package vibe.liteming.llmjs.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LLMConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_PROVIDER;
    public static final ForgeConfigSpec.IntValue TIMEOUT;
    public static final ForgeConfigSpec.IntValue RATE_LIMIT;
    public static final ForgeConfigSpec.IntValue MAX_PROMPT_LENGTH;
    public static final ForgeConfigSpec.IntValue MAX_IMAGE_BYTES;
    public static final ForgeConfigSpec.IntValue MAX_IMAGE_WIDTH;
    public static final ForgeConfigSpec.IntValue LOG_BUFFER_SIZE;
    public static final ForgeConfigSpec.IntValue REQUIRE_OP_LEVEL;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ALL_PLAYERS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ADMIN_UUID_WHITELIST;
    public static final ForgeConfigSpec.LongValue PERSONAL_BUDGET_LIMIT;

    static {
        BUILDER.push("general");
        DEFAULT_PROVIDER = BUILDER.comment("Default provider name (must match a key in providers.json)").define("default_provider", "openai");
        TIMEOUT = BUILDER.comment("Per-request timeout in seconds").defineInRange("timeout", 30, 1, 300);
        RATE_LIMIT = BUILDER.comment("Max requests per minute globally. 0 = unlimited").defineInRange("rate_limit", 30, 0, 1000);
        MAX_PROMPT_LENGTH = BUILDER.comment("Max characters per prompt (prevents abuse via network packets)").defineInRange("max_prompt_length", 10000, 100, 100000);
        MAX_IMAGE_BYTES = BUILDER.comment("Max compressed screenshot upload size in bytes").defineInRange("max_image_bytes", 786432, 32768, 2097152);
        MAX_IMAGE_WIDTH = BUILDER.comment("Max compressed screenshot width/height sent to LLM").defineInRange("max_image_width", 1024, 128, 2048);
        LOG_BUFFER_SIZE = BUILDER.comment("Number of log entries to keep in ring buffer").defineInRange("log_buffer_size", 200, 10, 10000);
        BUILDER.pop();

        BUILDER.push("permission");
        REQUIRE_OP_LEVEL = BUILDER.comment("Minimum OP level required to view Console logs").defineInRange("require_op_level", 2, 0, 4);
        ALLOW_ALL_PLAYERS = BUILDER.comment(
                "Server-only: allow every player to view Console logs.",
                "WARNING: logs contain complete LLM request and response content.")
                .define("allow_all_players", false);
        ADMIN_UUID_WHITELIST = BUILDER.comment(
                "Player UUIDs allowed to manage providers, routing, and Console tests without OP level 4.",
                "Online-mode UUIDs are authenticated. Offline-mode identities require a trusted external account system.",
                "OP level 2 only grants log viewing by default.")
                .defineListAllowEmpty("admin_uuid_whitelist", List.of(), LLMConfig::isUuid);
        PERSONAL_BUDGET_LIMIT = BUILDER.comment(
                "Maximum cumulative input + output tokens charged to each player. 0 = unlimited.",
                "Usage is stored per world by UUID. Attempts without provider usage metadata are charged",
                "their full conservative reservation. Offline-mode UUIDs require a trusted account system.")
                .defineInRange("personal_budget_limit", 0L, 0L, Long.MAX_VALUE);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register() {
        // Keep the established filename while runtime ownership moves to llmcore.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "llmjs-server.toml");
    }

    public static boolean setAdministrator(UUID playerId, boolean enabled) {
        if (playerId == null) return false;
        String expected = playerId.toString();
        List<String> next = new ArrayList<>(ADMIN_UUID_WHITELIST.get());
        boolean present = next.stream()
                .anyMatch(value -> value != null && expected.equalsIgnoreCase(value.strip()));
        if (enabled == present) return false;

        if (enabled) {
            next.add(expected);
        } else {
            next.removeIf(value -> value != null && expected.equalsIgnoreCase(value.strip()));
        }
        ADMIN_UUID_WHITELIST.set(List.copyOf(next));
        SPEC.save();
        return true;
    }

    public static void setPersonalBudgetLimit(long tokens) {
        PERSONAL_BUDGET_LIMIT.set(Math.max(0L, tokens));
        SPEC.save();
    }

    private static boolean isUuid(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            UUID.fromString(text.strip());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
