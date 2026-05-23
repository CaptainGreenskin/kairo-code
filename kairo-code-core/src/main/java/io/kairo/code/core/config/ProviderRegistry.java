package io.kairo.code.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for "what LLM providers does kairo-code understand".
 *
 * <p>Used to be hardcoded in nine places (CLI {@code VALID_PROVIDERS}, server
 * {@code resolveBaseUrl} switches, {@code ConfigController.getModels}, web
 * {@code SettingsModal.PROVIDERS}, application.yml defaults, two doc pages,
 * and a {@code CodeAgentFactory.buildModelProvider} switch). The lists drifted
 * — frontend called Zhipu's provider {@code "zhipu"} while CLI called it
 * {@code "glm"}, and {@code resolveBaseUrl} only knew about openai+anthropic
 * so glm/qianwen sessions fell back to the wrong endpoint.
 *
 * <p>All callers should now go through {@link #byId(String)} or
 * {@link #knownIds()}. New providers are added in exactly one place: the
 * static initializer below.
 */
public final class ProviderRegistry {

    public record ProviderSpec(
            /** Canonical lowercase id used on the wire (CLI flag, REST API, properties file). */
            String id,
            /** Human-readable name for UI dropdowns. */
            String displayName,
            /** Base URL used when the user doesn't supply one. */
            String defaultBaseUrl,
            /** Model id used when the user doesn't supply one. */
            String defaultModel,
            /** Models commonly used with this provider; surfaced in the web UI's Model picker. */
            List<String> knownModels
    ) {}

    private static final Map<String, ProviderSpec> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        register(new ProviderSpec(
                "openai",
                "OpenAI",
                "https://api.openai.com",
                "gpt-4o",
                List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo")));
        register(new ProviderSpec(
                "anthropic",
                "Anthropic",
                "https://api.anthropic.com",
                "claude-sonnet-4-20250514",
                List.of("claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-4-5-20251001")));
        register(new ProviderSpec(
                "glm",
                "智谱 (GLM)",
                "https://open.bigmodel.cn/api/coding/paas/v4",
                "glm-5.1",
                List.of("glm-5.1", "glm-4-plus", "glm-4-flash", "glm-4-long")));
        register(new ProviderSpec(
                "qianwen",
                "通义千问",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-max",
                List.of("qwen-max", "qwen-plus", "qwen-turbo")));

        // Legacy aliases — settle on the canonical id but accept inbound traffic
        // that still uses the old name so a single rename doesn't strand users
        // mid-deploy. New aliases here, NOT new ProviderSpec rows.
        ALIASES.put("zhipu", "glm");
    }

    private ProviderRegistry() {}

    private static void register(ProviderSpec spec) {
        BY_ID.put(spec.id(), spec);
    }

    /** Canonical lowercase ids the system understands. */
    public static Set<String> knownIds() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    public static List<ProviderSpec> all() {
        return List.copyOf(BY_ID.values());
    }

    /**
     * Look up a provider by id. Accepts case-insensitive input and known
     * aliases (e.g. {@code zhipu} → {@code glm}). Empty / null → empty.
     */
    public static Optional<ProviderSpec> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String norm = id.toLowerCase(Locale.ROOT).trim();
        String canonical = ALIASES.getOrDefault(norm, norm);
        return Optional.ofNullable(BY_ID.get(canonical));
    }

    /** Single canonical id, after alias resolution. Pass-through for unknowns. */
    public static String normalizeId(String id) {
        if (id == null) return null;
        String norm = id.toLowerCase(Locale.ROOT).trim();
        return ALIASES.getOrDefault(norm, norm);
    }

    public static boolean isKnown(String id) {
        return byId(id).isPresent();
    }

    /**
     * Default base URL for the provider. Falls back to the OpenAI endpoint
     * for unknown ids so legacy "custom" workflows still hit somewhere
     * predictable. Replaces the duplicate switches in
     * {@code AgentService.resolveBaseUrl} and
     * {@code AgentWebSocketHandler.resolveBaseUrl}.
     */
    public static String resolveBaseUrl(String providerId) {
        return byId(providerId).map(ProviderSpec::defaultBaseUrl).orElse("https://api.openai.com");
    }

    public static String defaultModel(String providerId) {
        return byId(providerId).map(ProviderSpec::defaultModel).orElse("gpt-4o");
    }

    /**
     * Models surfaced in the web UI's Model dropdown. Flat union across all
     * providers — kept in registry order so the picker shows OpenAI first,
     * then Anthropic, then GLM, then Qianwen.
     */
    public static List<String> allKnownModels() {
        return BY_ID.values().stream()
                .flatMap(s -> s.knownModels().stream())
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }
}
