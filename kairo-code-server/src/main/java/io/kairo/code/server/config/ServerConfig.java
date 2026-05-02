package io.kairo.code.server.config;

import io.kairo.code.core.CodeAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Map;

@Configuration
public class ServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    @Value("${kairo.code.working-dir}")
    private String workingDir;

    @Value("${kairo.code.api-key:}")
    private String apiKey;

    @Value("${kairo.code.model:gpt-4o}")
    private String model;

    @Value("${kairo.code.provider:openai}")
    private String provider;

    @Value("${kairo.code.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${kairo.code.thinking-budget:#{null}}")
    private Integer thinkingBudget;

    private final ConfigPersistenceService configPersistenceService;

    public ServerConfig(ConfigPersistenceService configPersistenceService) {
        this.configPersistenceService = configPersistenceService;
    }

    @Bean
    public CodeAgentConfig defaultAgentConfig() {
        ServerProperties props = serverProperties();
        return new CodeAgentConfig(
                props.apiKey(),
                props.baseUrl(),
                props.model(),
                50,
                props.workingDir(),
                null,
                0,
                0
        );
    }

    @Bean
    public ServerProperties serverProperties() {
        Map<String, String> persisted = configPersistenceService.load();

        // Env vars (from @Value) take precedence over persisted config.
        // For each field: if the @Value resolved to a non-default/non-empty value, use it.
        // Otherwise, fall back to persisted value.
        String resolvedApiKey = resolve("apiKey", apiKey, persisted);
        String resolvedModel = resolve("model", model, persisted);
        String resolvedProvider = resolve("provider", provider, persisted);
        String resolvedBaseUrl = resolve("baseUrl", baseUrl, persisted);
        String resolvedWorkingDir = resolve("workingDir", workingDir, persisted);
        Integer resolvedThinkingBudget = resolveThinkingBudget(thinkingBudget, persisted);

        if (StringUtils.hasText(resolvedApiKey)) {
            log.info("Using API key from {}",
                    StringUtils.hasText(apiKey) ? "environment" : "config file");
        }

        return new ServerProperties(
                resolvedProvider,
                resolvedModel,
                resolvedWorkingDir,
                resolvedBaseUrl,
                resolvedApiKey,
                resolvedThinkingBudget
        );
    }

    private String resolve(String key, String envValue, Map<String, String> persisted) {
        if (StringUtils.hasText(envValue)) {
            return envValue;
        }
        String persistedValue = persisted.get(key);
        return persistedValue != null ? persistedValue : "";
    }

    private Integer resolveThinkingBudget(Integer envValue, Map<String, String> persisted) {
        if (envValue != null) {
            return envValue;
        }
        String persistedValue = persisted.get("thinkingBudget");
        if (persistedValue != null) {
            try {
                return Integer.parseInt(persistedValue);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Mutable holder for server properties.
     * Supports hot-updates without restarting Spring.
     */
    public static class ServerProperties {
        private volatile String provider;
        private volatile String model;
        private volatile String workingDir;
        private volatile String baseUrl;
        private volatile String apiKey;
        private volatile Integer thinkingBudget;

        public ServerProperties(String provider, String model, String workingDir,
                                String baseUrl, String apiKey, Integer thinkingBudget) {
            this.provider = provider;
            this.model = model;
            this.workingDir = workingDir;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.thinkingBudget = thinkingBudget;
        }

        public String provider() { return provider; }
        public String model() { return model; }
        public String workingDir() { return workingDir; }
        public String baseUrl() { return baseUrl; }
        public String apiKey() { return apiKey; }
        public Integer thinkingBudget() { return thinkingBudget; }

        public void setProvider(String provider) { this.provider = provider; }
        public void setModel(String model) { this.model = model; }
        public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public void setThinkingBudget(Integer thinkingBudget) { this.thinkingBudget = thinkingBudget; }
    }
}
