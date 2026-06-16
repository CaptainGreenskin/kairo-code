package io.kairo.code.server.config;

import io.kairo.api.evolution.EvolvedSkill;
import io.kairo.api.evolution.EvolvedSkillStore;
import io.kairo.api.evolution.EvolutionPolicy;
import io.kairo.api.evolution.SkillTrustLevel;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.evolution.KairoEvolutionPolicy;
import io.kairo.code.core.skill.FsSkillLoader;
import io.kairo.evolution.EvolutionPipelineOrchestrator;
import io.kairo.evolution.EvolutionStateMachine;
import io.kairo.evolution.InMemoryEvolutionRuntimeStateStore;
import io.kairo.evolution.curator.CuratorActionExecutor;
import io.kairo.api.evolution.SkillTelemetryStore;
import io.kairo.evolution.curator.InMemorySkillTelemetryStore;
import io.kairo.evolution.curator.LlmSkillCurator;
import io.kairo.evolution.curator.PrefixClusterCurator;
import io.kairo.evolution.curator.SkillCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name = "kairo.evolution.enabled", havingValue = "true")
public class EvolutionConfig {

    private static final Logger log = LoggerFactory.getLogger(EvolutionConfig.class);

    @Bean
    @Primary
    EvolutionPolicy kairoEvolutionPolicy(
            EvolvedSkillStore skillStore,
            ServerConfig.ServerProperties props) {
        String modelName = resolveModel(props);
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.warn("Evolution: no API key — using noop policy");
            return new KairoEvolutionPolicy(null, modelName, 999, skillStore, Duration.ofSeconds(120));
        }
        ModelProvider mp = CodeAgentFactory.buildModelProvider(
                props.apiKey(), resolveBaseUrl(props), modelName);
        int threshold = 8;
        log.info("Evolution: using KairoEvolutionPolicy (model={}, threshold={}, timeout=120s)",
                modelName, threshold);
        return new KairoEvolutionPolicy(mp, modelName, threshold, skillStore, Duration.ofSeconds(120));
    }

    @Bean
    @Primary
    EvolutionPipelineOrchestrator kairoEvolutionOrchestrator(
            EvolutionPolicy policy,
            EvolvedSkillStore skillStore,
            EvolutionStateMachine stateMachine,
            InMemoryEvolutionRuntimeStateStore stateStore) {
        log.info("Evolution: wiring orchestrator with policy={}", policy.getClass().getSimpleName());
        return new EvolutionPipelineOrchestrator(policy, skillStore, stateMachine, stateStore);
    }

    @Bean
    @Primary
    io.kairo.code.core.evolution.KairoEvolutionHook kairoEvolutionHook(
            EvolvedSkillStore skillStore,
            EvolutionPipelineOrchestrator orchestrator) {
        log.info("Evolution: wiring KairoEvolutionHook (minIterations=2)");
        return new io.kairo.code.core.evolution.KairoEvolutionHook(skillStore, orchestrator, 2);
    }

    @Bean
    @Primary
    LlmSkillCurator prefixClusterCurator() {
        return new PrefixClusterCurator(2);
    }

    // ── Curator execution pipeline ─────────────────────────────────────────────

    @Bean
    SkillTelemetryStore skillTelemetryStore() {
        return new InMemorySkillTelemetryStore();
    }

    @Bean
    CuratorActionExecutor curatorActionExecutor(
            EvolvedSkillStore skillStore,
            SkillTelemetryStore telemetryStore) {
        return new CuratorActionExecutor(skillStore, telemetryStore, null);
    }

    @Bean(destroyMethod = "")
    @SuppressWarnings("unused")
    Object curatorScheduler(
            LlmSkillCurator curator,
            CuratorActionExecutor executor,
            EvolvedSkillStore skillStore) {
        long intervalMinutes = resolveReviewIntervalMinutes();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kairo-curator");
            t.setDaemon(true);
            return t;
        });
        sched.scheduleAtFixedRate(() -> {
            try {
                var skills = skillStore.list().collectList().block();
                if (skills == null || skills.size() < 2) return;
                var entries = skills.stream()
                        .map(s -> new SkillCatalog.Entry(s, null))
                        .toList();
                var catalog = new SkillCatalog(entries, java.util.List.of());
                var actions = curator.propose(catalog).block();
                if (actions == null || actions.isEmpty()) return;
                var report = executor.apply(actions);
                log.info("Curator review: {} actions proposed, {} applied, {} skipped",
                        actions.size(), report.applied().size(), report.skipped().size());
                if (!report.applied().isEmpty()) {
                    syncSkillsToFilesystem(skillStore);
                }
            } catch (Exception e) {
                log.warn("Curator review failed: {}", e.getMessage());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("Curator scheduler started (interval={}min)", intervalMinutes);
        return sched;
    }

    // ── Skill preload ──────────────────────────────────────────────────────────

    @Bean
    @SuppressWarnings("unused")
    Object evolvedSkillPreloader(EvolvedSkillStore skillStore) {
        Path globalDir = Paths.get(System.getProperty("user.home"), ".kairo-code", "skills");
        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        int loaded = 0;
        for (var sw : loader.loadAll()) {
            var meta = sw.metadata();
            String description = meta.definition().description() != null
                    ? meta.definition().description() : meta.name();
            String instructions = readSkillBody(globalDir, meta.name(), description);
            EvolvedSkill skill = new EvolvedSkill(
                    meta.name(),
                    meta.definition().version() != null ? meta.definition().version() : "1.0.0",
                    description,
                    instructions,
                    meta.definition().category() != null ? meta.definition().category().name() : "coding",
                    Set.of(),
                    SkillTrustLevel.VALIDATED,
                    null,
                    Instant.now(), Instant.now(), 0);
            skillStore.save(skill).block();
            loaded++;
        }
        if (loaded > 0) {
            log.info("Evolution: preloaded {} skill(s) from {} into EvolvedSkillStore", loaded, globalDir);
        }
        return new Object();
    }

    private static String readSkillBody(Path dir, String name, String fallback) {
        try {
            String raw = java.nio.file.Files.readString(dir.resolve(name + ".md"));
            int endOfFrontmatter = raw.indexOf("---", 3);
            if (endOfFrontmatter > 0) {
                String body = raw.substring(endOfFrontmatter + 3).strip();
                if (!body.isEmpty()) return body;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    public static void syncSkillsToFilesystem(EvolvedSkillStore skillStore) {
        Path dir = Paths.get(System.getProperty("user.home"), ".kairo-code", "skills");
        try {
            java.nio.file.Files.createDirectories(dir);
            var skills = skillStore.list().collectList().block();
            if (skills == null) return;
            java.util.Set<String> activeNames = new java.util.HashSet<>();
            for (var s : skills) {
                activeNames.add(s.name());
                Path file = dir.resolve(s.name() + ".md");
                String content = "---\nname: " + s.name()
                        + "\ndescription: " + s.description()
                        + "\nversion: " + s.version()
                        + "\ncategory: " + (s.category() != null ? s.category() : "GENERAL")
                        + "\n---\n\n# " + s.name() + "\n\n" + s.instructions() + "\n";
                java.nio.file.Files.writeString(file, content);
            }
            // Delete archived skills from fs
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .filter(p -> !activeNames.contains(
                                p.getFileName().toString().replace(".md", "")))
                        .forEach(p -> {
                            try { java.nio.file.Files.delete(p); } catch (Exception ignored) {}
                        });
            }
            log.info("Synced {} skills to {}", activeNames.size(), dir);
        } catch (Exception e) {
            log.warn("Failed to sync skills to filesystem: {}", e.getMessage());
        }
    }

    private static long resolveReviewIntervalMinutes() {
        String env = System.getenv("KAIRO_CURATOR_INTERVAL_MINUTES");
        if (env != null && !env.isBlank()) {
            try {
                long v = Long.parseLong(env.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {}
        }
        return 60;
    }

    private String resolveModel(ServerConfig.ServerProperties props) {
        if (props.model() != null && !props.model().isBlank()) return props.model();
        return "glm-5.1";
    }

    private String resolveBaseUrl(ServerConfig.ServerProperties props) {
        if (props.baseUrl() != null && !props.baseUrl().isBlank()) return props.baseUrl();
        return io.kairo.code.core.config.ProviderRegistry.resolveBaseUrl(
                props.provider() != null ? props.provider() : "glm");
    }
}
