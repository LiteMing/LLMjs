package vibe.liteming.llmjs.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Client-local Console colors. Missing entries intentionally use deterministic automatic colors. */
@OnlyIn(Dist.CLIENT)
public final class ConsoleColorStore {
    public enum Group { ROUTE, MODEL }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Integer> ROUTE_COLORS = new LinkedHashMap<>();
    private static final Map<String, Integer> MODEL_COLORS = new LinkedHashMap<>();
    private static boolean loaded;

    private ConsoleColorStore() {}

    public static synchronized int resolve(Group group, String tag, int fallback) {
        ensureLoaded();
        Integer manual = colors(group).get(normalizeTag(tag));
        return manual != null ? manual : automaticColor(tag, fallback);
    }

    public static synchronized Integer manualColor(Group group, String tag) {
        ensureLoaded();
        return colors(group).get(normalizeTag(tag));
    }

    public static synchronized void setColor(Group group, String tag, int color) {
        ensureLoaded();
        String key = normalizeTag(tag);
        if (key.isEmpty()) return;
        colors(group).put(key, color);
        save();
    }

    public static synchronized void clearColor(Group group, String tag) {
        ensureLoaded();
        if (colors(group).remove(normalizeTag(tag)) != null) save();
    }

    public static synchronized Set<String> configuredTags(Group group) {
        ensureLoaded();
        return new LinkedHashSet<>(colors(group).keySet());
    }

    public static int automaticColor(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        int h = value.hashCode() * 0x45d9f3b;
        int r = 80 + ((h >>> 16) & 0x7F);
        int g = 80 + ((h >>> 8) & 0x7F);
        int b = 80 + (h & 0x7F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Accepts RRGGBB or AARRGGBB, with optional # or 0x prefix. */
    public static Integer parseColor(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
        if (value.length() != 6 && value.length() != 8) return null;
        try {
            long parsed = Long.parseUnsignedLong(value, 16);
            if (value.length() == 6) parsed |= 0xFF000000L;
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String formatColor(int color) {
        return String.format(java.util.Locale.ROOT, "%08X", color);
    }

    private static Map<String, Integer> colors(Group group) {
        return group == Group.ROUTE ? ROUTE_COLORS : MODEL_COLORS;
    }

    private static String normalizeTag(String tag) {
        return tag == null ? "" : tag.trim();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path file = configFile();
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            readColors(root.get("routes"), ROUTE_COLORS);
            readColors(root.get("models"), MODEL_COLORS);
        } catch (Exception ignored) {
            ROUTE_COLORS.clear();
            MODEL_COLORS.clear();
        }
    }

    private static void readColors(JsonElement element, Map<String, Integer> target) {
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            Integer color = parseColor(entry.getValue().getAsString());
            if (color != null && !entry.getKey().isBlank()) target.put(entry.getKey(), color);
        }
    }

    private static void save() {
        Path file = configFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.add("routes", writeColors(ROUTE_COLORS));
            root.add("models", writeColors(MODEL_COLORS));
            Files.writeString(temp, GSON.toJson(root));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignoredCleanup) {
            }
        }
    }

    private static JsonObject writeColors(Map<String, Integer> source) {
        JsonObject object = new JsonObject();
        source.forEach((tag, color) -> object.addProperty(tag, formatColor(color)));
        return object;
    }

    private static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("llmcore-console-colors.json");
    }
}
