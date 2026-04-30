package io.kairo.code.server.controller;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.dto.FileContentResponse;
import io.kairo.code.server.dto.FileEntry;
import io.kairo.code.server.dto.ServerConfigResponse;
import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * REST controller for server configuration and session management.
 */
@RestController
@RequestMapping("/api")
public class ConfigController {

    private final ServerProperties serverProperties;
    private final AgentService agentService;
    private final Path workingDir;

    public ConfigController(ServerProperties serverProperties,
                            AgentService agentService) {
        this.serverProperties = serverProperties;
        this.agentService = agentService;
        this.workingDir = Paths.get(serverProperties.workingDir());
    }

    /**
     * Return the current server configuration.
     */
    @GetMapping("/config")
    public ServerConfigResponse getConfig() {
        return new ServerConfigResponse(
                serverProperties.provider(),
                serverProperties.model(),
                serverProperties.workingDir());
    }

    /**
     * Return the list of available models.
     */
    @GetMapping("/models")
    public List<String> getModels() {
        return List.of(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514",
                "glm-4-plus"
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

    private static final long MAX_FILE_SIZE = 100_000;

    /**
     * List directory contents (files + subdirectories).
     */
    @GetMapping("/files")
    public List<FileEntry> listFiles(@RequestParam(defaultValue = "") String path) {
        Path resolved = resolvePath(path);
        Path dir = workingDir.resolve(path.isBlank() ? "" : path).normalize();

        // Path traversal protection
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

        // Path traversal protection
        if (!resolved.startsWith(workingDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }

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

        String language = inferLanguage(resolved.getFileName().toString());
        return new FileContentResponse(path, content, language);
    }

    /**
     * Resolve a relative path against the working directory, with path traversal checks.
     */
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

    /**
     * Infer code language from file extension.
     */
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
