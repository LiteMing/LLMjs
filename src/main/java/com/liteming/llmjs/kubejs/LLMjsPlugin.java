package com.liteming.llmjs.kubejs;

import com.liteming.llmjs.util.LLMUtil;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

public class LLMjsPlugin extends KubeJSPlugin {

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("LLM", LLMUtil.INSTANCE);
    }
}