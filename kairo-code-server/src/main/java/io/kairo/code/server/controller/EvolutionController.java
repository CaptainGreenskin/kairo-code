package io.kairo.code.server.controller;

import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.evolution.LearnedLessonStore.Lesson;
import io.kairo.code.core.evolution.LearnedLessonStore.Status;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/evolution")
public class EvolutionController {

    private final ServerProperties props;

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

    private LearnedLessonStore store() {
        String workingDir = props.workingDir();
        return workingDir != null
            ? new LearnedLessonStore(Paths.get(workingDir).resolve(".kairo-code").resolve("learned.json"))
            : new LearnedLessonStore(Paths.get(System.getProperty("user.home"), ".kairo-code", "learned.json"));
    }
}
