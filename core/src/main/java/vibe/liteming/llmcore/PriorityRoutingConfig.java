package vibe.liteming.llmcore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable routing snapshot. Provider-list accessors are projections for existing consumers. */
public final class PriorityRoutingConfig {
    private final Map<String, LlmRoute> purposeRoutes;
    private final LlmRoute defaultRoute;
    private final Map<String, LlmRouteOptions> purposeOptions;

    public PriorityRoutingConfig(Map<String, List<String>> purposeChains, List<String> defaultChain) {
        this(purposeChains, defaultChain, Map.of());
    }

    public PriorityRoutingConfig(Map<String, List<String>> purposeChains, List<String> defaultChain,
            Map<String, LlmRouteOptions> purposeOptions) {
        this(LlmRoute.sequential(defaultChain), routes(purposeChains), purposeOptions);
    }

    public PriorityRoutingConfig(LlmRoute defaultRoute, Map<String, LlmRoute> purposeRoutes,
            Map<String, LlmRouteOptions> purposeOptions) {
        this.defaultRoute = defaultRoute == null ? LlmRoute.empty() : defaultRoute;
        Map<String, LlmRoute> routes = new LinkedHashMap<>();
        if (purposeRoutes != null) purposeRoutes.forEach((key, route) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("purpose is required");
            if (route != null && !route.isUnset()) routes.put(key.trim(), route);
        });
        this.purposeRoutes = Collections.unmodifiableMap(routes);
        Map<String, LlmRouteOptions> options = new LinkedHashMap<>();
        if (purposeOptions != null) purposeOptions.forEach((key, value) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("purpose is required");
            if (value != null && !value.isEmpty()) options.put(key.trim(), value);
        });
        this.purposeOptions = Collections.unmodifiableMap(options);
    }

    private static Map<String, LlmRoute> routes(Map<String, List<String>> chains) {
        Map<String, LlmRoute> result = new LinkedHashMap<>();
        if (chains != null) chains.forEach((key, value) -> result.put(key, LlmRoute.sequential(value)));
        return result;
    }

    public static PriorityRoutingConfig empty() {
        return new PriorityRoutingConfig(LlmRoute.empty(), Map.of(), Map.of());
    }

    public Map<String, LlmRoute> purposeRoutes() { return purposeRoutes; }
    public LlmRoute defaultRoute() { return defaultRoute; }
    public Map<String, LlmRouteOptions> purposeOptions() { return purposeOptions; }
    public List<String> defaultChain() { return defaultRoute.providers(); }

    public Map<String, List<String>> purposeChains() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        purposeRoutes.forEach((purpose, route) -> result.put(purpose, route.providers()));
        return Collections.unmodifiableMap(result);
    }

    public LlmRoute resolveRoute(String purpose, List<String> fallback) {
        LlmRoute route = purpose == null ? null : purposeRoutes.get(purpose.trim());
        LlmRoute inherited = defaultRoute.isEmpty()
                ? LlmRoute.sequential(fallback).withDeadline(defaultRoute.deadlineOverrideSeconds()) : defaultRoute;
        if (route == null) return inherited;
        return new LlmRoute(route.isEmpty() ? inherited.stages() : route.stages(),
                route.deadlineOverrideSeconds() == null ? inherited.deadlineOverrideSeconds() : route.deadlineOverrideSeconds());
    }

    public List<String> resolveChain(String purpose, List<String> fallback) {
        return resolveRoute(purpose, fallback).providers();
    }

    public LlmRouteOptions resolveOptions(String purpose) {
        return purpose == null ? LlmRouteOptions.empty()
                : purposeOptions.getOrDefault(purpose.trim(), LlmRouteOptions.empty());
    }

    public PriorityRoutingConfig withPurpose(String purpose, List<String> chain) {
        return withPurposeRoute(purpose, LlmRoute.sequential(chain));
    }

    public PriorityRoutingConfig withPurposeRoute(String purpose, LlmRoute route) {
        String key = purpose == null ? "" : purpose.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("purpose is required");
        Map<String, LlmRoute> next = new LinkedHashMap<>(purposeRoutes);
        if (route == null || route.isUnset()) next.remove(key);
        else next.put(key, route);
        return new PriorityRoutingConfig(defaultRoute, next, purposeOptions);
    }

    public PriorityRoutingConfig withDefault(List<String> chain) {
        return withDefaultRoute(LlmRoute.sequential(chain));
    }

    public PriorityRoutingConfig withDefaultRoute(LlmRoute route) {
        return new PriorityRoutingConfig(route, purposeRoutes, purposeOptions);
    }

    public PriorityRoutingConfig withPurposeOptions(String purpose, LlmRouteOptions options) {
        String key = purpose == null ? "" : purpose.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("purpose is required");
        Map<String, LlmRouteOptions> next = new LinkedHashMap<>(purposeOptions);
        if (options == null || options.isEmpty()) next.remove(key);
        else next.put(key, options);
        return new PriorityRoutingConfig(defaultRoute, purposeRoutes, next);
    }

    @Override public boolean equals(Object other) {
        return other instanceof PriorityRoutingConfig config && purposeRoutes.equals(config.purposeRoutes)
                && defaultRoute.equals(config.defaultRoute) && purposeOptions.equals(config.purposeOptions);
    }

    @Override public int hashCode() { return Objects.hash(purposeRoutes, defaultRoute, purposeOptions); }
    @Override public String toString() { return RoutingConfigStore.toJsonString(this); }
}
