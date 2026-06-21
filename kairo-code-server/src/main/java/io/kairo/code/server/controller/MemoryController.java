package io.kairo.code.server.controller;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.core.memory.FileMemoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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

    @org.springframework.beans.factory.annotation.Autowired
    private io.kairo.code.server.config.ServerConfig.ServerProperties props;

    public MemoryController() {
        Path memoryDir = Path.of(System.getProperty("user.home"), ".kairo-code", "memory");
        this.store = new FileMemoryStore(memoryDir);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "AGENT") String scope,
            @RequestParam(required = false) String search) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Source 1: FileMemoryStore (auto-extracted JSON memories)
        MemoryScope ms = parseScope(scope);
        var flux = (search != null && !search.isBlank())
                ? store.search(search, ms)
                : store.list(ms);
        List<MemoryEntry> entries = flux.collectList().block();
        if (entries != null) {
            result.addAll(entries.stream().map(this::toMap).toList());
        }

        // Source 2: Structured .md memories (memory_write tool output)
        result.addAll(loadStructuredMemories(search));

        return result;
    }

    private List<Map<String, Object>> loadStructuredMemories(String search) {
        String workingDir = props.workingDir();
        if (workingDir == null) return List.of();
        Path memDir = Path.of(workingDir, ".kairo", "memory");
        if (!Files.isDirectory(memDir)) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(memDir)) {
            files.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals("MEMORY.md"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         String name = p.getFileName().toString().replace(".md", "");
                         String body = extractBody(content);
                         String type = extractFrontmatter(content, "type");
                         String desc = extractFrontmatter(content, "description");

                         if (search != null && !search.isBlank()) {
                             String q = search.toLowerCase();
                             if (!body.toLowerCase().contains(q) && !name.toLowerCase().contains(q)) return;
                         }

                         Map<String, Object> m = new LinkedHashMap<>();
                         m.put("id", "md:" + name);
                         m.put("content", body.isBlank() ? desc : body);
                         m.put("scope", type != null ? type.toUpperCase() : "AGENT");
                         m.put("importance", 0.8);
                         m.put("tags", List.of("structured", type != null ? type : "memory"));
                         m.put("timestamp", Files.getLastModifiedTime(p).toInstant().toString());
                         m.put("agentId", "kairo-code");
                         m.put("source", "memory_write");
                         result.add(m);
                     } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
        return result;
    }

    private static String extractBody(String markdown) {
        int end = markdown.indexOf("---", 3);
        if (end < 0) return markdown;
        return markdown.substring(end + 3).trim();
    }

    private static String extractFrontmatter(String markdown, String key) {
        int end = markdown.indexOf("---", 3);
        if (end < 0) return null;
        String fm = markdown.substring(0, end);
        for (String line : fm.split("\n")) {
            if (line.trim().startsWith(key + ":")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return null;
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
