package io.kairo.code.server.controller;

import io.kairo.api.evolution.EvolvedSkillStore;
import io.kairo.api.evolution.SkillTrustLevel;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.evolution.LearnedLessonStore.Lesson;
import io.kairo.code.core.evolution.LearnedLessonStore.Status;
import io.kairo.code.core.skill.FsSkillLoader;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController("lessonController")
@RequestMapping("/api/evolution")
public class EvolutionController {

    private final ServerProperties props;

    @Autowired(required = false)
    private EvolvedSkillStore evolvedSkillStore;

    @Autowired(required = false)
    private io.kairo.evolution.curator.LlmSkillCurator curator;

    @Autowired(required = false)
    private io.kairo.evolution.curator.CuratorActionExecutor curatorExecutor;

    public EvolutionController(ServerProperties props) {
        this.props = props;
    }

    @GetMapping("/lessons")
    public List<Lesson> listLessons() {
        var store = store();
        return store.list();
    }

    @PutMapping("/lessons/{id}/status")
    public ResponseEntity<Map<String, String>> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String statusStr = body.getOrDefault("status", "");
        Status status;
        try {
            status = Status.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid status: " + statusStr));
        }
        var store = store();
        var existing = store.list().stream().filter(l -> l.id().equals(id)).findFirst();
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        var updated = new Lesson(existing.get().id(), existing.get().toolName(), existing.get().lessonText(), status, existing.get().timestamp());
        store.save(updated);
        return ResponseEntity.ok(Map.of("id", id, "status", status.name()));
    }

    @DeleteMapping("/lessons/{id}")
    public ResponseEntity<Void> deleteLesson(@PathVariable String id) {
        var store = store();
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Evolved skills (InMemoryEvolvedSkillStore — runtime evolution output) ----

    @GetMapping("/evolved-skills")
    public List<Map<String, Object>> listEvolvedSkills() {
        if (evolvedSkillStore == null) return List.of();
        return evolvedSkillStore.listByMinTrust(SkillTrustLevel.DRAFT)
                .map(s -> Map.<String, Object>of(
                        "name", s.name(),
                        "version", s.version(),
                        "description", s.description(),
                        "trustLevel", s.trustLevel().name(),
                        "category", s.category() != null ? s.category() : "",
                        "instructions", s.instructions() != null ? s.instructions() : ""))
                .collectList()
                .block();
    }

    @PostMapping("/curator/review")
    public ResponseEntity<List<Map<String, Object>>> triggerCuratorReview() {
        if (curator == null || evolvedSkillStore == null) {
            return ResponseEntity.ok(List.of());
        }
        var skills = evolvedSkillStore.list().collectList().block();
        if (skills == null || skills.isEmpty()) return ResponseEntity.ok(List.of());
        var entries = skills.stream()
                .map(s -> new io.kairo.evolution.curator.SkillCatalog.Entry(s, null))
                .collect(java.util.stream.Collectors.toList());
        var catalog = new io.kairo.evolution.curator.SkillCatalog(entries, List.of());
        var actions = curator.propose(catalog).block();
        if (actions == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(actions.stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", a.getClass().getSimpleName());
            if (a instanceof io.kairo.evolution.curator.CuratorAction.MergeIntoUmbrella merge) {
                m.put("umbrella", merge.umbrella());
                m.put("siblings", merge.siblings());
                m.put("reason", merge.rationale());
            }
            return m;
        }).toList());
    }

    @PostMapping("/curator/execute")
    public ResponseEntity<Map<String, Object>> executeCuratorReview() {
        if (curator == null || evolvedSkillStore == null || curatorExecutor == null) {
            return ResponseEntity.ok(Map.of("status", "not_available"));
        }
        var skills = evolvedSkillStore.list().collectList().block();
        if (skills == null || skills.size() < 2) {
            return ResponseEntity.ok(Map.of("status", "not_enough_skills", "count", skills != null ? skills.size() : 0));
        }
        var entries = skills.stream()
                .map(s -> new io.kairo.evolution.curator.SkillCatalog.Entry(s, null))
                .collect(java.util.stream.Collectors.toList());
        var catalog = new io.kairo.evolution.curator.SkillCatalog(entries, List.of());
        var actions = curator.propose(catalog).block();
        if (actions == null || actions.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_actions"));
        }
        var report = curatorExecutor.apply(actions);
        if (!report.applied().isEmpty()) {
            io.kairo.code.server.config.EvolutionConfig.syncSkillsToFilesystem(evolvedSkillStore);
        }
        return ResponseEntity.ok(Map.of(
                "status", "executed",
                "applied", report.applied().size(),
                "skipped", report.skipped().size()));
    }

    // ---- Skills endpoints (filesystem) ----

    @GetMapping("/skills")
    public List<Map<String, String>> listSkills() {
        FsSkillLoader loader = skillLoader();
        return loader.loadAll().stream().map(s -> {
            String desc = s.metadata().definition().description();
            return Map.of(
                    "name", s.metadata().name(),
                    "description", desc != null ? desc : "",
                    "priority", s.priority().name(),
                    "visibility", s.metadata().visibility().name()
            );
        }).toList();
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String name) {
        Path managedDir = globalSkillsDir().resolve("managed");
        Path target = managedDir.resolve(name + ".md");
        if (!Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            Files.delete(target);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private FsSkillLoader skillLoader() {
        return new FsSkillLoader(globalSkillsDir(), projectSkillsDir());
    }

    private Path globalSkillsDir() {
        return Paths.get(System.getProperty("user.home"), ".kairo-code", "skills");
    }

    private Path projectSkillsDir() {
        String workingDir = props.workingDir();
        return workingDir != null
                ? Paths.get(workingDir).resolve(".kairo-code").resolve("skills")
                : null;
    }

    private LearnedLessonStore store() {
        String workingDir = props.workingDir();
        return workingDir != null
            ? new LearnedLessonStore(Paths.get(workingDir).resolve(".kairo-code").resolve("learned.json"))
            : new LearnedLessonStore(Paths.get(System.getProperty("user.home"), ".kairo-code", "learned.json"));
    }
}
