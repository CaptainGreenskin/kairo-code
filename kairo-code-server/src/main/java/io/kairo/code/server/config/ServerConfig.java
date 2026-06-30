package io.kairo.code.server.config;

import io.kairo.api.agent.AgentBuilderCustomizer;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.LlmClassifierConfig;
import io.kairo.core.agent.AgentBuilder;
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

    // Built-in default = the first provider in ProviderRegistry (openai). Defaults
    // live in the registry so changing the canonical default (e.g. gpt-4o → gpt-5
    // when OpenAI ships it) doesn't need a code change here. Sentinel lives here
    // (not in @Value) so resolve() can tell "user set it" apart from "Spring
    // filled in the fallback".
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_MODEL =
            io.kairo.code.core.config.ProviderRegistry.defaultModel(DEFAULT_PROVIDER);
    private static final String DEFAULT_BASE_URL =
            io.kairo.code.core.config.ProviderRegistry.resolveBaseUrl(DEFAULT_PROVIDER);

    @Value("${kairo.code.working-dir}")
    private String workingDir;

    @Value("${kairo.code.api-key:}")
    private String apiKey;

    @Value("${kairo.code.model:}")
    private String model;

    @Value("${kairo.code.provider:}")
    private String provider;

    @Value("${kairo.code.base-url:}")
    private String baseUrl;

    @Value("${kairo.code.thinking-budget:#{null}}")
    private Integer thinkingBudget;

    // LLM-backed bash classifier (DangerousCommandPolicy.UNKNOWN fallback). Defaults to enabled
    // because (a) the dogfood server is the canonical observability target — we need the
    // SecurityEvent + langfuse span flowing in real traffic to know the wiring actually works;
    // (b) cost is bounded (only fires when the static regex returns UNKNOWN, then LRU-cached);
    // (c) heuristic-only mode is still one env flip away (kairo.code.bash.llm-classifier.enabled=false).
    @Value("${kairo.code.bash.llm-classifier.enabled:true}")
    private boolean llmClassifierEnabled;

    // Blank → reuses CodeAgentConfig.modelName so the fallback rides on the agent's provider
    // (e.g. glm-5.1) — no separate auth path to maintain.
    @Value("${kairo.code.bash.llm-classifier.model:}")
    private String llmClassifierModel;

    @Value("${kairo.code.bash.llm-classifier.cache-size:512}")
    private int llmClassifierCacheSize;

    @Value("${kairo.code.bash.llm-classifier.timeout-millis:5000}")
    private long llmClassifierTimeoutMillis;

    private final ConfigPersistenceService configPersistenceService;
    private final WorkspacePersistenceService workspacePersistenceService;

    public ServerConfig(ConfigPersistenceService configPersistenceService,
                        WorkspacePersistenceService workspacePersistenceService) {
        this.configPersistenceService = configPersistenceService;
        this.workspacePersistenceService = workspacePersistenceService;
    }

    @Bean
    public CodeAgentConfig defaultAgentConfig() {
        ServerProperties props = serverProperties();
        return new CodeAgentConfig(
                props.apiKey(),
                props.baseUrl(),
                props.model(),
                Integer.MAX_VALUE,
                props.workingDir(),
                null,
                0,
                0,
                props.thinkingBudget(),
                props.llmClassifier()
        );
    }

    /**
     * Process-wide {@link io.kairo.api.cron.CronScheduler}. Backed by an on-disk task store under
     * {@code ~/.kairo-code/cron/}, fired via a headless agent session per task (see {@link
     * io.kairo.code.service.cron.HeadlessCronFireCallback}). Wired into {@link
     * io.kairo.code.core.CodeAgentFactory} as the global scheduler so every agent session gets the
     * cron tools (CronCreate/Delete/List/Edit/Pause/Resume/Trigger). Bootstrap failure degrades
     * gracefully: cron disabled, tools not registered.
     */
    @Bean(destroyMethod = "stop")
    public io.kairo.api.cron.CronScheduler cronScheduler(
            io.kairo.code.service.AgentService agentService) {
        try {
            java.nio.file.Path cronFile =
                    java.nio.file.Path.of(
                            System.getProperty("user.home"), ".kairo-code", "cron", "tasks.json");
            java.nio.file.Files.createDirectories(cronFile.getParent());
            io.kairo.cron.CronTaskStore store = new io.kairo.cron.CronTaskStore(cronFile);
            io.kairo.api.cron.CronFireCallback callback =
                    new io.kairo.code.service.cron.HeadlessCronFireCallback(
                            agentService, defaultAgentConfig());
            io.kairo.cron.DefaultCronScheduler scheduler =
                    new io.kairo.cron.DefaultCronScheduler(
                            store, callback, java.time.ZoneId.systemDefault());
            scheduler.start();
            io.kairo.code.core.CodeAgentFactory.setGlobalCronScheduler(scheduler);
            log.info("CronScheduler started (store={})", cronFile);
            return scheduler;
        } catch (Exception e) {
            log.warn("CronScheduler bootstrap failed, cron tools disabled: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Override the auto-configured {@code openaiModelProvider} so SwarmCoordinator workers (and any
     * other {@code @Autowired ModelProvider} consumers) see the GLM-aware build. The upstream
     * starter's 2-arg {@code OpenAIProvider} forces a {@code /v1/chat/completions} suffix, which
     * collides with GLM's {@code /api/coding/paas/v4} base (404 on {@code /v4/v1/chat/completions}).
     * {@link CodeAgentFactory#buildModelProvider} already routes URLs ending in {@code /v\d+} to the
     * 3-arg constructor with an explicit {@code /chat/completions} path; reuse it here so server
     * and session agents share one provider-resolution policy.
     */
    @Bean
    public io.kairo.api.model.ModelProvider modelProvider() {
        ServerProperties props = serverProperties();
        if (!org.springframework.util.StringUtils.hasText(props.apiKey())) {
            log.warn("No API key configured — server starts in onboarding mode. "
                    + "Configure via web UI or --kairo.code.api-key=...");
            return new io.kairo.api.model.ModelProvider() {
                @Override
                public reactor.core.publisher.Mono<io.kairo.api.model.ModelResponse> call(
                        java.util.List<io.kairo.api.message.Msg> messages,
                        io.kairo.api.model.ModelConfig config) {
                    return reactor.core.publisher.Mono.error(
                            new IllegalStateException("API key not configured. "
                                    + "Please configure via Settings."));
                }

                @Override
                public reactor.core.publisher.Flux<io.kairo.api.model.ModelResponse> stream(
                        java.util.List<io.kairo.api.message.Msg> messages,
                        io.kairo.api.model.ModelConfig config) {
                    return reactor.core.publisher.Flux.error(
                            new IllegalStateException("API key not configured."));
                }

                @Override
                public String name() {
                    return "unconfigured";
                }
            };
        }
        return io.kairo.code.core.CodeAgentFactory.buildModelProvider(
                props.apiKey(), props.baseUrl(), props.model());
    }

    @Bean
    AgentBuilderCustomizer modelNameCustomizer(ServerProperties props) {
        return builder -> ((AgentBuilder) builder).modelName(props.model());
    }

    @Bean
    public ServerProperties serverProperties() {
        Map<String, String> persisted = configPersistenceService.load();

        // Precedence: env (@Value) > config.properties > built-in default.
        // @Value defaults are empty so a missing env var falls through to persisted.
        String resolvedApiKey = orDefault(resolve("apiKey", apiKey, persisted), "");
        String resolvedModel = orDefault(resolve("model", model, persisted), DEFAULT_MODEL);
        String resolvedProvider = orDefault(resolve("provider", provider, persisted), DEFAULT_PROVIDER);
        String resolvedBaseUrl = orDefault(resolve("baseUrl", baseUrl, persisted), DEFAULT_BASE_URL);
        String resolvedWorkingDir = resolve("workingDir", workingDir, persisted);
        Integer resolvedThinkingBudget = resolveThinkingBudget(thinkingBudget, persisted);

        if (StringUtils.hasText(resolvedApiKey)) {
            log.info("Using API key from {}",
                    StringUtils.hasText(apiKey) ? "environment" : "config file");
        }

        // Bootstrap default workspace using legacy workingDir as the fallback. After this returns,
        // workspaces.json is guaranteed non-empty for fresh installs, so the rest of the system
        // can rely on having at least one workspace to attach sessions to.
        workspacePersistenceService.bootstrapIfEmpty(resolvedWorkingDir);

        LlmClassifierConfig llmClassifier =
                new LlmClassifierConfig(
                        llmClassifierEnabled,
                        StringUtils.hasText(llmClassifierModel) ? llmClassifierModel : null,
                        llmClassifierCacheSize,
                        llmClassifierTimeoutMillis);

        return new ServerProperties(
                resolvedProvider,
                resolvedModel,
                resolvedWorkingDir,
                resolvedBaseUrl,
                resolvedApiKey,
                resolvedThinkingBudget,
                llmClassifier
        );
    }

    private String resolve(String key, String envValue, Map<String, String> persisted) {
        if (StringUtils.hasText(envValue)) {
            return envValue;
        }
        String persistedValue = persisted.get(key);
        return persistedValue != null ? persistedValue : "";
    }

    private String orDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
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
        private volatile LlmClassifierConfig llmClassifier;

        public ServerProperties(String provider, String model, String workingDir,
                                String baseUrl, String apiKey, Integer thinkingBudget,
                                LlmClassifierConfig llmClassifier) {
            this.provider = provider;
            this.model = model;
            this.workingDir = workingDir;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.thinkingBudget = thinkingBudget;
            this.llmClassifier = llmClassifier != null
                    ? llmClassifier
                    : LlmClassifierConfig.disabled();
        }

        public ServerProperties(String provider, String model, String workingDir,
                                String baseUrl, String apiKey, Integer thinkingBudget) {
            this(provider, model, workingDir, baseUrl, apiKey, thinkingBudget,
                    LlmClassifierConfig.disabled());
        }

        public ServerProperties(String provider, String model, String workingDir,
                                String baseUrl, String apiKey) {
            this(provider, model, workingDir, baseUrl, apiKey, null,
                    LlmClassifierConfig.disabled());
        }

        public String provider() { return provider; }
        public String model() { return model; }
        public String workingDir() { return workingDir; }
        public String baseUrl() { return baseUrl; }
        public String apiKey() { return apiKey; }
        public Integer thinkingBudget() { return thinkingBudget; }
        public LlmClassifierConfig llmClassifier() { return llmClassifier; }

        public void setProvider(String provider) { this.provider = provider; }
        public void setModel(String model) { this.model = model; }
        public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public void setThinkingBudget(Integer thinkingBudget) { this.thinkingBudget = thinkingBudget; }
        public void setLlmClassifier(LlmClassifierConfig llmClassifier) {
            this.llmClassifier = llmClassifier != null
                    ? llmClassifier
                    : LlmClassifierConfig.disabled();
        }
    }
}
