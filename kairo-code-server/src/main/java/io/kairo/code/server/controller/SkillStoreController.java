package io.kairo.code.server.controller;

import io.kairo.code.core.skill.SkillInstaller;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class SkillStoreController {

    private static final Logger log = LoggerFactory.getLogger(SkillStoreController.class);
    private final SkillInstaller installer = new SkillInstaller();

    @GetMapping("/managed")
    public List<Map<String, Object>> listManaged() {
        return installer.list().stream()
                .map(s -> Map.<String, Object>of(
                        "name", s.name(),
                        "gitUrl", s.gitUrl() != null ? s.gitUrl() : "",
                        "path", s.path().toString()))
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
            SkillInstaller.InstalledSkill result = installer.install(source);
            return ResponseEntity.ok(Map.of(
                    "name", result.name(),
                    "gitUrl", result.gitUrl() != null ? result.gitUrl() : "",
                    "path", result.path().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "already_installed", "message", e.getMessage()));
        } catch (Exception e) {
            log.warn("Skill install failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "install_failed", "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> uninstall(@PathVariable String name) {
        try {
            boolean removed = installer.uninstall(name);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
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
            if (!updated) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("name", name, "status", "updated"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "update_failed", "message", e.getMessage()));
        }
    }
}
