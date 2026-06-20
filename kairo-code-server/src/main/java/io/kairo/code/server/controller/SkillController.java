package io.kairo.code.server.controller;

import io.kairo.code.core.skill.FsSkillLoader;
import io.kairo.code.core.skill.SkillInstaller;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    @Autowired
    private io.kairo.code.server.config.ServerConfig.ServerProperties props;

    private final SkillInstaller installer = new SkillInstaller();

    @GetMapping
    public List<Map<String, Object>> listAll() {
        FsSkillLoader loader = new FsSkillLoader(globalSkillsDir(), projectSkillsDir());
        List<Map<String, Object>> result = new ArrayList<>();

        for (var s : loader.loadAll()) {
            var def = s.metadata().definition();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", def.name());
            entry.put("description", def.description() != null ? def.description() : "");
            entry.put("category", def.category().name());
            entry.put("priority", s.priority().name());
            entry.put("visibility", s.metadata().visibility().name());
            entry.put("triggers", def.triggerConditions() != null ? def.triggerConditions() : List.of());
            entry.put("version", def.version());
            entry.put("hasInstructions", def.hasInstructions());
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/{name}/detail")
    public ResponseEntity<?> getDetail(@PathVariable String name) {
        FsSkillLoader loader = new FsSkillLoader(globalSkillsDir(), projectSkillsDir());
        for (var sw : loader.loadAll()) {
            if (sw.metadata().name().equals(name)) {
                var def = sw.metadata().definition();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", def.name());
                result.put("description", def.description() != null ? def.description() : "");
                result.put("instructions", def.instructions() != null ? def.instructions() : "");
                result.put("category", def.category().name());
                result.put("version", def.version());
                result.put("triggers", def.triggerConditions() != null ? def.triggerConditions() : List.of());
                return ResponseEntity.ok(result);
            }
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/managed")
    public List<Map<String, Object>> listManaged() {
        return installer.list().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.name());
                    m.put("gitUrl", s.gitUrl() != null ? s.gitUrl() : "");
                    m.put("path", s.path().toString());
                    return m;
                })
                .toList();
    }

    @PostMapping("/install")
    public ResponseEntity<?> install(@RequestBody Map<String, String> body) {
        String source = body.getOrDefault("source", "").trim();
        if (source.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "message", "source is required"));
        }
        try {
            var result = installer.install(source);
            return ResponseEntity.ok(Map.of(
                    "name", result.name(),
                    "gitUrl", result.gitUrl() != null ? result.gitUrl() : "",
                    "path", result.path().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "already_installed", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "install_failed", "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> uninstall(@PathVariable String name) {
        try {
            boolean removed = installer.uninstall(name);
            if (!removed) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("name", name, "status", "uninstalled"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "uninstall_failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/{name}/update")
    public ResponseEntity<?> update(@PathVariable String name) {
        try {
            boolean updated = installer.update(name);
            if (!updated) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("name", name, "status", "updated"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "update_failed", "message", e.getMessage()));
        }
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
}
