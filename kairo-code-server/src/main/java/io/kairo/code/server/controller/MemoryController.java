package io.kairo.code.server.controller;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.core.memory.FileMemoryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryStore store;

    public MemoryController() {
        Path memoryDir = Path.of(System.getProperty("user.home"), ".kairo-code", "memory");
        this.store = new FileMemoryStore(memoryDir);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "AGENT") String scope,
            @RequestParam(required = false) String search) {
        MemoryScope ms = parseScope(scope);
        var flux = (search != null && !search.isBlank())
                ? store.search(search, ms)
                : store.list(ms);
        List<MemoryEntry> entries = flux.collectList().block();
        if (entries == null) return List.of();
        return entries.stream().map(this::toMap).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        MemoryEntry entry = store.get(id).block();
        if (entry == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toMap(entry));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String content = (String) body.getOrDefault("content", "");
        if (content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        String scopeStr = (String) body.getOrDefault("scope", "AGENT");
        double importance = body.containsKey("importance")
                ? ((Number) body.get("importance")).doubleValue() : 0.5;
        @SuppressWarnings("unchecked")
        List<String> tagList = (List<String>) body.getOrDefault("tags", List.of());

        MemoryEntry entry = new MemoryEntry(
                UUID.randomUUID().toString(),
                "kairo-code",
                content,
                null,
                parseScope(scopeStr),
                importance,
                null,
                Set.copyOf(tagList),
                Instant.now(),
                Map.of("source", "web-ui"));

        MemoryEntry saved = store.save(entry).block();
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        MemoryEntry existing = store.get(id).block();
        if (existing == null) return ResponseEntity.notFound().build();

        String content = (String) body.getOrDefault("content", existing.content());
        double importance = body.containsKey("importance")
                ? ((Number) body.get("importance")).doubleValue() : existing.importance();
        @SuppressWarnings("unchecked")
        List<String> tagList = body.containsKey("tags")
                ? (List<String>) body.get("tags") : List.copyOf(existing.tags());

        MemoryEntry updated = new MemoryEntry(
                existing.id(),
                existing.agentId(),
                content,
                existing.rawContent(),
                existing.scope(),
                importance,
                existing.embedding(),
                Set.copyOf(tagList),
                existing.timestamp(),
                existing.metadata());

        MemoryEntry saved = store.save(updated).block();
        return ResponseEntity.ok(toMap(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        store.delete(id).block();
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toMap(MemoryEntry e) {
        if (e == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("content", e.content() != null ? e.content() : "");
        m.put("scope", e.scope() != null ? e.scope().name() : "AGENT");
        m.put("importance", e.importance());
        m.put("tags", e.tags() != null ? List.copyOf(e.tags()) : List.of());
        m.put("timestamp", e.timestamp() != null ? e.timestamp().toString() : null);
        m.put("agentId", e.agentId());
        return m;
    }

    private static MemoryScope parseScope(String s) {
        try {
            return MemoryScope.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return MemoryScope.AGENT;
        }
    }
}
