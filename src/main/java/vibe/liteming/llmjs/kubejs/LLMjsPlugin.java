package vibe.liteming.llmjs.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

public class LLMjsPlugin extends KubeJSPlugin {
    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("LLM", LLMBinding.INSTANCE);
    }
}
