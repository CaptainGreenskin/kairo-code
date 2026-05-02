package io.kairo.code.server.controller;

import io.kairo.code.server.config.ConfigPersistenceService;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.dto.FileContentResponse;
import io.kairo.code.server.dto.FileEntry;
import io.kairo.code.server.dto.SearchMatch;
import io.kairo.code.server.dto.SearchResponse;
import io.kairo.code.server.dto.ServerConfigResponse;
import io.kairo.code.server.dto.UpdateConfigRequest;
import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * REST controller for server configuration and session management.
 */
@RestController
@RequestMapping("/api")
public class ConfigController {

    private final ServerProperties serverProperties;
    private final AgentService agentService;
    private final ConfigPersistenceService persistenceService;
    private final Path workingDir;

    public ConfigController(ServerProperties serverProperties,
                            AgentService agentService,
                            ConfigPersistenceService persistenceService) {
        this.serverProperties = serverProperties;
        this.agentService = agentService;
        this.persistenceService = persistenceService;
        this.workingDir = Paths.get(serverProperties.workingDir());
    }

    /**
     * Return the current server configuration.
     */
    @GetMapping("/config")
    public ServerConfigResponse getConfig() {
        return buildConfigResponse();
    }

    /**
     * Update server configuration (partial update, persisted + hot-updated).
     */
    @PostMapping("/config")
    public ServerConfigResponse updateConfig(@RequestBody UpdateConfigRequest request) throws IOException {
        Map<String, String> current = new HashMap<>(persistenceService.load());

        if (request.apiKey() != null) current.put("apiKey", request.apiKey());
        if (request.model() != null) current.put("model", request.model());
        if (request.provider() != null) current.put("provider", request.provider());
        if (request.baseUrl() != null) current.put("baseUrl", request.baseUrl());
        if (request.workingDir() != null) current.put("workingDir", request.workingDir());
        if (request.thinkingBudget() != null) current.put("thinkingBudget", String.valueOf(request.thinkingBudget()));

        persistenceService.save(current);

        if (request.provider() != null) serverProperties.setProvider(request.provider());
        if (request.model() != null) serverProperties.setModel(request.model());
        if (request.baseUrl() != null) serverProperties.setBaseUrl(request.baseUrl());
        if (request.workingDir() != null) serverProperties.setWorkingDir(request.workingDir());
        if (request.apiKey() != null) serverProperties.setApiKey(request.apiKey());
        if (request.thinkingBudget() != null) serverProperties.setThinkingBudget(request.thinkingBudget());

        agentService.updateDefaultConfig(
                serverProperties.apiKey(),
                serverProperties.model(),
                serverProperties.provider(),
                serverProperties.baseUrl(),
                serverProperties.workingDir(),
                serverProperties.thinkingBudget()
        );

        return buildConfigResponse();
    }

    /**
     * Return the list of available models.
     */
    @GetMapping("/models")
    public List<String> getModels() {
        return List.of(
                // OpenAI
                "gpt-4o", "gpt-4o-mini", "gpt-4-turbo",
                // Anthropic
                "claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-4-5-20251001",
                // Zhipu (GLM)
                "glm-5.1", "glm-4-plus", "glm-4-flash", "glm-4-long",
                // Qianwen
                "qwen-max", "qwen-plus", "qwen-turbo"
        );
    }

    /**
     * Return the list of active sessions.
     */
    @GetMapping("/sessions")
    public List<SessionInfo> getSessions() {
        return agentService.listSessions();
    }

    /**
     * Destroy a session by ID.
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> destroySession(@PathVariable String id) {
        boolean destroyed = agentService.destroySession(id);
        return destroyed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", ".idea", "build", "dist", ".gradle", "__pycache__"
    );
    private static final int MAX_FILES_WALK = 10_000;
    private static final int MAX_PREVIEW_LENGTH = 120;
    private static final int HARD_LIMIT = 200;

    /**
     * Search file contents in the working directory.
     */
    @GetMapping("/search")
    public SearchResponse searchFiles(
            @RequestParam String q,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (q == null || q.length() < 2) {
            return new SearchResponse(q != null ? q : "", List.of(), false);
        }

        int cappedLimit = Math.min(Math.max(limit, 1), HARD_LIMIT);

        Path base = workingDir;
        if (!path.isBlank()) {
            if (path.contains("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
            }
            base = workingDir.resolve(path).normalize();
            if (!base.startsWith(workingDir)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
            }
        }

        List<SearchMatch> results = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicBoolean hasMore = new AtomicBoolean(false);

        try (Stream<Path> stream = Files.walk(base, Integer.MAX_VALUE)) {
            stream
                    .filter(p -> {
                        // Cap total files walked
                        return fileCount.incrementAndGet() <= MAX_FILES_WALK;
                    })
                    .filter(p -> {
                        // Skip noise directories
                        Path relative = workingDir.relativize(p);
                        String first = relative.getNameCount() > 0 ? relative.getName(0).toString() : "";
                        return !SKIP_DIRS.contains(first);
                    })
                    .filter(Files::isRegularFile)
                    .filter(p -> !isBinary(p))
                    .forEach(p -> {
                        if (results.size() >= cappedLimit) {
                            hasMore.set(true);
                            return;
                        }
                        String relative = workingDir.relativize(p).toString();
                        searchInFile(p, relative, q.toLowerCase(), results, cappedLimit, hasMore);
                    });
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to search files", e);
        }

        return new SearchResponse(q, results, hasMore.get());
    }

    private void searchInFile(Path file, String relativePath, String queryLower,
                              List<SearchMatch> results, int limit, AtomicBoolean hasMore) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (results.size() >= limit) {
                    hasMore.set(true);
                    break;
                }
                if (line.toLowerCase().contains(queryLower)) {
                    String preview = line.trim();
                    if (preview.length() > MAX_PREVIEW_LENGTH) {
                        preview = preview.substring(0, MAX_PREVIEW_LENGTH);
                    }
                    results.add(new SearchMatch(relativePath, lineNum, preview));
                }
            }
        } catch (MalformedInputException e) {
            // Skip non-UTF-8 files
        } catch (IOException ignored) {
            // Skip files that can't be read
        }
    }

    private boolean isBinary(Path file) {
        try (var is = Files.newInputStream(file)) {
            byte[] header = is.readNBytes(512);
            for (byte b : header) {
                if (b == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static final long MAX_FILE_SIZE = 100_000;

    /**
     * List subdirectories of an arbitrary absolute path for the directory picker.
     * No workingDir restriction — any readable directory on the server is allowed.
     */
    @GetMapping("/dirs")
    public List<Map<String, String>> listDirs(@RequestParam(defaultValue = "") String path) {
        Path target;
        if (path.isBlank()) {
            target = Paths.get(System.getProperty("user.home"));
        } else {
            target = Paths.get(path);
        }

        if (!Files.exists(target) || !Files.isDirectory(target) || !Files.isReadable(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found: " + path);
        }

        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        try { return Files.isReadable(p); } catch (Exception e) { return false; }
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .map(p -> Map.of("name", p.getFileName().toString(), "path", p.toAbsolutePath().toString()))
                    .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list directory", e);
        }
    }

    /**
     * List directory contents (files + subdirectories).
     */
    @GetMapping("/files")
    public List<FileEntry> listFiles(@RequestParam(defaultValue = "") String path) {
        Path dir = workingDir.resolve(path.isBlank() ? "" : path).normalize();

        if (!dir.startsWith(workingDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found: " + path);
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> {
                        String relative = workingDir.relativize(p).toString();
                        try {
                            boolean isDir = Files.isDirectory(p);
                            long size = isDir ? 0 : Files.size(p);
                            return new FileEntry(p.getFileName().toString(), relative, isDir, size);
                        } catch (IOException e) {
                            return new FileEntry(p.getFileName().toString(), relative, false, 0);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list directory", e);
        }
    }

    /**
     * Read file content (limit 100KB, returns 413 if exceeded).
     */
    @GetMapping("/files/content")
    public FileContentResponse getFileContent(@RequestParam String path) {
        Path resolved = resolvePath(path);

        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
        }

        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file size", e);
        }

        if (size > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File too large: " + (size / 1024) + "KB exceeds 100KB limit");
        }

        String content;
        try {
            content = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is not a valid UTF-8 text file");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file", e);
        }

        return new FileContentResponse(path, content, inferLanguage(resolved.getFileName().toString()));
    }

    /**
     * Write file content (strict workingDir boundary check).
     */
    @PutMapping("/files/content")
    public Map<String, String> putFileContent(
            @RequestParam String path,
            @RequestBody String content) {
        Path resolved = resolvePath(path);

        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return Map.of("path", path, "status", "ok");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write file", e);
        }
    }

    /**
     * Delete a file or directory (recursive) within workingDir.
     */
    @DeleteMapping("/files")
    public ResponseEntity<Void> deleteFile(@RequestParam String path) {
        Path resolved = resolvePath(path);
        if (!Files.exists(resolved)) {
            return ResponseEntity.notFound().build();
        }
        try {
            if (Files.isDirectory(resolved)) {
                try (Stream<Path> walk = Files.walk(resolved)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            } else {
                Files.delete(resolved);
            }
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete", e);
        }
    }

    /**
     * Rename / move a file or directory within workingDir.
     */
    @PostMapping("/files/rename")
    public Map<String, String> renameFile(@RequestParam String from, @RequestParam String to) {
        Path src = resolvePath(from);
        Path dst = resolvePath(to);
        if (!Files.exists(src)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found: " + from);
        }
        try {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Map.of("from", from, "to", to, "status", "ok");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to rename", e);
        }
    }

    /**
     * Create a new directory within workingDir.
     */
    @PostMapping("/files/mkdir")
    public Map<String, String> mkdir(@RequestParam String path) {
        Path resolved = resolvePath(path);
        try {
            Files.createDirectories(resolved);
            return Map.of("path", path, "status", "ok");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create directory", e);
        }
    }

    private Path resolvePath(String path) {
        if (path.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }
        Path target = workingDir.resolve(path).normalize();
        if (!target.startsWith(workingDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }
        return target;
    }

    private ServerConfigResponse buildConfigResponse() {
        return new ServerConfigResponse(
                serverProperties.provider(),
                serverProperties.model(),
                serverProperties.workingDir(),
                serverProperties.baseUrl(),
                serverProperties.apiKey() != null && !serverProperties.apiKey().isBlank(),
                serverProperties.thinkingBudget()
        );
    }

    private static String inferLanguage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return switch (fileName.substring(dot + 1).toLowerCase()) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "js" -> "javascript";
            case "jsx" -> "jsx";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "cs" -> "csharp";
            case "cpp", "cc", "cxx" -> "cpp";
            case "c", "h" -> "c";
            case "scala" -> "scala";
            case "groovy" -> "groovy";
            case "yaml", "yml" -> "yaml";
            case "json" -> "json";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "scss" -> "scss";
            case "sh", "bash" -> "bash";
            case "sql" -> "sql";
            case "md" -> "markdown";
            case "toml" -> "toml";
            case "properties" -> "properties";
            case "gradle" -> "groovy";
            default -> "";
        };
    }
}
