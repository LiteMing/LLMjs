package com.liteming.llmjs.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@FunctionalInterface
public interface PostProcessor {
    String process(String input);

    static PostProcessor extract(String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        return input -> {
            Matcher matcher = pattern.matcher(input);
            return matcher.find() ? matcher.group() : input;
        };
    }

    static PostProcessor replace(String regex, String replacement) {
        Pattern pattern = Pattern.compile(regex);
        return input -> pattern.matcher(input).replaceAll(replacement);
    }
}
