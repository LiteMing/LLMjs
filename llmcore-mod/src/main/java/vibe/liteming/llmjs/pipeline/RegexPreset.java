package vibe.liteming.llmjs.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegexPreset {
    private static final Map<String, RegexPreset> REGISTRY = new ConcurrentHashMap<>();

    private final String name;
    private final List<PostProcessor> processors;

    public RegexPreset(String name, List<PostProcessor> processors) {
        this.name = name;
        this.processors = new ArrayList<>(processors);
    }

    public String apply(String input) {
        String result = input;
        for (PostProcessor p : processors) result = p.process(result);
        return result;
    }

    public static void register(String name, RegexPreset preset) { REGISTRY.put(name, preset); }
    public static RegexPreset get(String name) { return REGISTRY.get(name); }
    public static Map<String, RegexPreset> getAll() { return REGISTRY; }
}
