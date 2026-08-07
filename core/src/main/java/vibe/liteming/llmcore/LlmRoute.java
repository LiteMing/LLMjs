// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** A bounded fallback sequence. Candidates in one stage race using complete responses. */
public record LlmRoute(List<Stage> stages, Integer deadlineOverrideSeconds) {
    public static final int DEFAULT_DEADLINE_SECONDS = 120;
    public static final int MAX_STAGES = 16;
    public static final int MAX_PARALLEL = 8;
    public static final int MAX_RETRIES = 10;
    public static final int MAX_ATTEMPTS = 128;
    private static final Gson GSON = new Gson();

    public LlmRoute {
        stages = List.copyOf(stages == null ? List.of() : stages);
        if (stages.size() > MAX_STAGES) throw new IllegalArgumentException("route supports at most " + MAX_STAGES + " stages");
        if (deadlineOverrideSeconds != null && (deadlineOverrideSeconds < 1 || deadlineOverrideSeconds > 3600)) {
            throw new IllegalArgumentException("deadlineSeconds must be 1..3600");
        }
    }

    public record Target(String provider, Integer retries) {
        public Target {
            provider = provider == null ? "" : provider.trim();
            if (provider.isEmpty()) throw new IllegalArgumentException("provider name is blank");
            if (retries != null && (retries < 0 || retries > MAX_RETRIES)) {
                throw new IllegalArgumentException("retries must be 0.." + MAX_RETRIES);
            }
        }

        /** Unset means bounded credential failover: each credential may be tried once. */
        public int maxAttempts(int credentials) {
            return retries == null ? Math.min(MAX_ATTEMPTS, credentials) : retries + 1;
        }

        String expression() {
            String name = provider.matches("[\\p{L}\\p{N}_:./-]+") ? provider : GSON.toJson(provider);
            return name + (retries == null ? "" : "*" + retries);
        }
    }

    public record Stage(List<Target> candidates) {
        public Stage {
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
            if (candidates.isEmpty() || candidates.size() > MAX_PARALLEL) {
                throw new IllegalArgumentException("a stage needs 1.." + MAX_PARALLEL + " providers");
            }
            var names = new HashSet<String>();
            for (Target candidate : candidates) {
                if (!names.add(candidate.provider())) throw new IllegalArgumentException("duplicate provider in race: " + candidate.provider());
            }
        }

        public boolean racing() { return candidates.size() > 1; }
        String expression() {
            String value = candidates.stream().map(Target::expression).collect(java.util.stream.Collectors.joining(" | "));
            return racing() ? "(" + value + ")" : value;
        }
    }

    public static LlmRoute empty() { return sequential(List.of()); }
    public static LlmRoute sequential(List<String> providers) {
        return new LlmRoute((providers == null ? List.<String>of() : providers).stream()
                .map(name -> new Stage(List.of(new Target(name, null)))).toList(), null);
    }
    public boolean isEmpty() { return stages.isEmpty(); }
    public boolean isUnset() { return isEmpty() && deadlineOverrideSeconds == null; }
    public int deadlineSeconds() { return deadlineOverrideSeconds == null ? DEFAULT_DEADLINE_SECONDS : deadlineOverrideSeconds; }

    /** Inspection only; execution must preserve stages, retries and repeated providers. */
    public List<String> providers() {
        return stages.stream().flatMap(stage -> stage.candidates().stream()).map(Target::provider).toList();
    }
    public String expression() {
        return stages.stream().map(Stage::expression).collect(java.util.stream.Collectors.joining(" > "));
    }
    public LlmRoute withDeadline(Integer seconds) { return new LlmRoute(stages, seconds); }

    /** A*3 > (B | C) > A: initial A plus three retries, B/C race, then another A stage. */
    public static LlmRoute parse(String expression) { return new Parser(expression == null ? "" : expression).parse(); }

    private static final class Parser {
        private final String text;
        private int offset;
        Parser(String text) {
            if (text.length() > 8192) throw new IllegalArgumentException("route expression exceeds 8192 characters");
            this.text = text;
        }

        LlmRoute parse() {
            List<Stage> stages = new ArrayList<>();
            whitespace();
            while (offset < text.length()) {
                List<Target> candidates = new ArrayList<>();
                if (take('(')) {
                    candidates.add(target());
                    while (take('|')) candidates.add(target());
                    require(')');
                    if (candidates.size() < 2) throw error("a race needs at least two providers");
                } else candidates.add(target());
                stages.add(new Stage(candidates));
                if (stages.size() > MAX_STAGES) throw error("too many route stages");
                whitespace();
                if (offset == text.length()) break;
                require('>');
                whitespace();
                if (offset == text.length()) throw error("expected a provider after >");
            }
            return new LlmRoute(stages, null);
        }

        private Target target() {
            whitespace();
            String name;
            int start = offset;
            if (offset < text.length() && text.charAt(offset) == '"') {
                offset++;
                boolean escaped = false;
                boolean closed = false;
                while (offset < text.length()) {
                    char c = text.charAt(offset++);
                    if (!escaped && c == '"') { closed = true; break; }
                    if (!escaped && c == '\\') escaped = true;
                    else escaped = false;
                }
                if (!closed) throw error("unterminated provider name");
                try { name = JsonParser.parseString(text.substring(start, offset)).getAsString(); }
                catch (RuntimeException e) { throw error("invalid quoted provider name"); }
            } else {
                while (offset < text.length() && "*>|()\"".indexOf(text.charAt(offset)) < 0) offset++;
                name = text.substring(start, offset).trim();
            }
            if (name.isBlank()) throw error("expected a provider name");
            Integer retries = null;
            if (take('*')) {
                whitespace();
                int numberStart = offset;
                while (offset < text.length() && Character.isDigit(text.charAt(offset))) offset++;
                try { retries = Integer.parseInt(text.substring(numberStart, offset)); }
                catch (NumberFormatException e) { throw error("expected retry count after *"); }
            }
            return new Target(name, retries);
        }
        private boolean take(char c) {
            whitespace();
            if (offset == text.length() || text.charAt(offset) != c) return false;
            offset++;
            return true;
        }
        private void require(char c) { if (!take(c)) throw error("expected '" + c + "'"); }
        private void whitespace() { while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++; }
        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at route character " + (offset + 1));
        }
    }
}
