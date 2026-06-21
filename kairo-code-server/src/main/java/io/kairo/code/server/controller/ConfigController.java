package io.kairo.code.server.controller;

import io.kairo.code.server.config.ConfigPersistenceService;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.config.WorkspaceConfig;
import io.kairo.code.server.config.WorkspacePersistenceService;
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
    private final WorkspacePersistenceService workspaces;

    public ConfigController(ServerProperties serverProperties,
                            AgentService agentService,
                            ConfigPersistenceService persistenceService,
                            WorkspacePersistenceService workspaces) {
        this.serverProperties = serverProperties;
        this.agentService = agentService;
        this.persistenceService = persistenceService;
        this.workspaces = workspaces;
    }

    /**
     * Resolve the working directory for a request. When {@code workspaceId} is supplied
     * (post-M112), look up the workspace and use its {@code workingDir}; otherwise fall
     * back to the legacy {@link ServerProperties#workingDir()} (default workspace's dir).
     */
    private Path workingDir(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return workspaces.findById(workspaceId)
                    .map(WorkspaceConfig::workingDir)
                    .map(Paths::get)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));
        }
        return Paths.get(serverProperties.workingDir());
    }

    /**
     * Return the current server configuration.
     */
    @GetMapping("/config")
    public ServerConfigResponse getConfig() {
        return buildConfigResponse();
    }

    /**
     * Kubernetes / docker-compose health probe. Returns 200 only when:
     * (a) an API key is configured (caller has something to authenticate with), and
     * (b) the working directory is writeable (the agent has somewhere to put files).
     *
     * <p>Deliberately does NOT make a model call — that would burn quota on every probe
     * and could rate-limit real chat. Use this for liveness + readiness probes; use
     * {@code /actuator/health} for the lower-level "JVM up?" check.
     */
    @GetMapping("/server-info")
    public Map<String, Object> serverInfo(jakarta.servlet.http.HttpServletRequest request) {
        java.util.List<String> ips = new java.util.ArrayList<>();
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        int port = request.getServerPort();
        String lanUrl = ips.isEmpty() ? null : "http://" + ips.get(0) + ":" + port;
        return Map.of(
                "lanIps", ips,
                "port", port,
                "lanUrl", lanUrl != null ? lanUrl : "",
                "hostname", java.net.InetAddress.getLoopbackAddress().getHostName());
    }

    @GetMapping("/healthz")
    public ResponseEntity<HealthResponse> healthz() {
        boolean apiKeySet = serverProperties.apiKey() != null && !serverProperties.apiKey().isBlank();
        String workingDir = serverProperties.workingDir();
        boolean workingDirWriteable = false;
        String workingDirError = null;
        try {
            Path wd = Paths.get(workingDir);
            if (!Files.exists(wd)) {
                Files.createDirectories(wd);
            }
            Path probe = wd.resolve(".kairo-health-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            workingDirWriteable = true;
        } catch (IOException e) {
            workingDirError = e.getMessage();
        }
        boolean ok = apiKeySet && workingDirWriteable;
        HealthResponse body = new HealthResponse(
                ok ? "UP" : "DOWN",
                apiKeySet,
                workingDirWriteable,
                workingDir,
                workingDirError);
        return ok
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    public record HealthResponse(
            String status,
            boolean apiKeySet,
            boolean workingDirWriteable,
            String workingDir,
            String workingDirError) {}

    /**
     * Update server configuration (partial update, persisted + hot-updated).
     */
    @PostMapping("/config")
    public ServerConfigResponse updateConfig(@RequestBody UpdateConfigRequest request) throws IOException {
        Map<String, String> current = new HashMap<>(persistenceService.load());

        // Normalize provider on the way in. Frontend may have sent "zhipu"
        // (legacy id) or "OpenAI" (display case); turn both into the canonical
        // lowercase ProviderRegistry id before anything else touches it.
        // Reject unknown providers as 400 instead of persisting garbage.
        String normalizedProvider = null;
        if (request.provider() != null) {
            normalizedProvider = io.kairo.code.core.config.ProviderRegistry.normalizeId(request.provider());
            if (!io.kairo.code.core.config.ProviderRegistry.isKnown(normalizedProvider)
                    && !"custom".equals(normalizedProvider)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Unknown provider: " + request.provider()
                                + " (known: " + io.kairo.code.core.config.ProviderRegistry.knownIds() + ")");
            }
        }

        // If provider changed and the caller didn't also supply a baseUrl, the
        // old baseUrl is stale — it belongs to the previous provider. Recompute
        // from the registry. Without this, switching openai → anthropic in the
        // Settings UI would keep firing requests at api.openai.com.
        String effectiveBaseUrl = request.baseUrl();
        if (normalizedProvider != null
                && !normalizedProvider.equals(serverProperties.provider())
                && (request.baseUrl() == null || request.baseUrl().isBlank())) {
            effectiveBaseUrl = io.kairo.code.core.config.ProviderRegistry.resolveBaseUrl(normalizedProvider);
        }

        if (request.apiKey() != null) current.put("apiKey", request.apiKey());
        if (request.model() != null) current.put("model", request.model());
        if (normalizedProvider != null) current.put("provider", normalizedProvider);
        if (effectiveBaseUrl != null) current.put("baseUrl", effectiveBaseUrl);
        if (request.thinkingBudget() != null) current.put("thinkingBudget", String.valueOf(request.thinkingBudget()));

        persistenceService.save(current);

        if (normalizedProvider != null) serverProperties.setProvider(normalizedProvider);
        if (request.model() != null) serverProperties.setModel(request.model());
        if (effectiveBaseUrl != null) serverProperties.setBaseUrl(effectiveBaseUrl);
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
     * Return the list of available models. Pulled from {@link io.kairo.code.core.config.ProviderRegistry}
     * so the web UI's Model picker is always in sync with what the CLI accepts —
     * was previously a hand-maintained literal list that drifted from the actual
     * provider registry.
     */
    @GetMapping("/models")
    public List<String> getModels() {
        return io.kairo.code.core.config.ProviderRegistry.allKnownModels();
    }

    /**
     * Return the list of supported providers. Web UI's Provider dropdown should
     * call this instead of hardcoding its own list (which previously drifted —
     * frontend called Zhipu "zhipu" while CLI / config.properties called it "glm").
     */
    @GetMapping("/providers")
    public List<ProviderInfo> getProviders() {
        return io.kairo.code.core.config.ProviderRegistry.all().stream()
                .map(p -> new ProviderInfo(p.id(), p.displayName(), p.defaultBaseUrl(), p.defaultModel(), p.knownModels()))
                .toList();
    }

    public record ProviderInfo(
            String id,
            String displayName,
            String defaultBaseUrl,
            String defaultModel,
            List<String> knownModels) {}

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
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(defaultValue = "false") boolean regex,
            @RequestParam(defaultValue = "false") boolean caseSensitive,
            @RequestParam(defaultValue = "") String include,
            @RequestParam(defaultValue = "") String exclude,
            @RequestParam(defaultValue = "0") int contextLines
    ) {
        if (q == null || q.length() < 2) {
            return new SearchResponse(q != null ? q : "", List.of(), false);
        }

        int cappedLimit = Math.min(Math.max(limit, 1), HARD_LIMIT);
        int cappedContext = Math.min(Math.max(contextLines, 0), 5);

        java.util.regex.Pattern pattern;
        if (regex) {
            try {
                int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
                pattern = java.util.regex.Pattern.compile(q, flags);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid regex: " + e.getMessage());
            }
        } else {
            String escaped = java.util.regex.Pattern.quote(q);
            int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
            pattern = java.util.regex.Pattern.compile(escaped, flags);
        }

        java.nio.file.FileSystem fileSystem = java.nio.file.FileSystems.getDefault();
        java.nio.file.PathMatcher includeMatcher = include.isBlank() ? null
                : fileSystem.getPathMatcher("glob:" + include);
        java.nio.file.PathMatcher excludeMatcher = exclude.isBlank() ? null
                : fileSystem.getPathMatcher("glob:" + exclude);

        Path wd = workingDir(workspaceId);
        Path base = wd;
        if (!path.isBlank()) {
            if (path.contains("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
            }
            base = wd.resolve(path).normalize();
            if (!base.startsWith(wd)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
            }
        }

        List<SearchMatch> results = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicBoolean hasMore = new AtomicBoolean(false);

        try (Stream<Path> stream = Files.walk(base, Integer.MAX_VALUE)) {
            stream
                    .filter(p -> fileCount.incrementAndGet() <= MAX_FILES_WALK)
                    .filter(p -> {
                        Path relative = wd.relativize(p);
                        for (int i = 0; i < relative.getNameCount(); i++) {
                            if (SKIP_DIRS.contains(relative.getName(i).toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        Path fileName = p.getFileName();
                        if (includeMatcher != null && !includeMatcher.matches(fileName)) return false;
                        if (excludeMatcher != null && excludeMatcher.matches(fileName)) return false;
                        return true;
                    })
                    .filter(p -> !isBinary(p))
                    .forEach(p -> {
                        if (results.size() >= cappedLimit) {
                            hasMore.set(true);
                            return;
                        }
                        String relative = wd.relativize(p).toString();
                        searchInFile(p, relative, pattern, results, cappedLimit, hasMore, cappedContext);
                    });
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to search files", e);
        }

        return new SearchResponse(q, results, hasMore.get());
    }

    @GetMapping("/search/files")
    public List<String> searchFileNames(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String workspaceId
    ) {
        if (q == null || q.isBlank()) return List.of();
        String queryLower = q.toLowerCase();
        Path wd = workingDir(workspaceId);
        List<String> results = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);

        try (Stream<Path> stream = Files.walk(wd, Integer.MAX_VALUE)) {
            stream
                    .filter(p -> fileCount.incrementAndGet() <= MAX_FILES_WALK)
                    .filter(p -> {
                        Path relative = wd.relativize(p);
                        for (int i = 0; i < relative.getNameCount(); i++) {
                            if (SKIP_DIRS.contains(relative.getName(i).toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        String relative = wd.relativize(p).toString();
                        if (fuzzyMatch(relative.toLowerCase(), queryLower)) {
                            results.add(relative);
                        }
                    });
        } catch (IOException e) {
            // Return partial results
        }

        results.sort(java.util.Comparator.comparingInt(String::length));
        return results.subList(0, Math.min(results.size(), limit));
    }

    private static boolean fuzzyMatch(String text, String query) {
        int qi = 0;
        for (int ti = 0; ti < text.length() && qi < query.length(); ti++) {
            if (text.charAt(ti) == query.charAt(qi)) qi++;
        }
        return qi == query.length();
    }

    private void searchInFile(Path file, String relativePath, java.util.regex.Pattern pattern,
                              List<SearchMatch> results, int limit, AtomicBoolean hasMore,
                              int contextLines) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (results.size() >= limit) {
                    hasMore.set(true);
                    break;
                }
                if (pattern.matcher(lines.get(i)).find()) {
                    String preview = lines.get(i).trim();
                    if (preview.length() > MAX_PREVIEW_LENGTH) {
                        preview = preview.substring(0, MAX_PREVIEW_LENGTH);
                    }
                    List<String> before = List.of();
                    List<String> after = List.of();
                    if (contextLines > 0) {
                        int fromBefore = Math.max(0, i - contextLines);
                        int toAfter = Math.min(lines.size(), i + contextLines + 1);
                        before = lines.subList(fromBefore, i).stream().map(String::trim).toList();
                        after = (i + 1 < toAfter)
                                ? lines.subList(i + 1, toAfter).stream().map(String::trim).toList()
                                : List.of();
                    }
                    results.add(new SearchMatch(relativePath, i + 1, preview, before, after));
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

    /** Max byte size for in-editor reads. 2 MiB covers nearly all source files; Monaco
     *  starts to drag past this so we'd rather refuse than render a frozen tab. */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

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
     * Open the native OS directory picker dialog (macOS only).
     * Returns the selected path or null if the user cancelled.
     */
    @GetMapping("/choose-dir")
    public Map<String, String> chooseDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Native directory picker is only available on macOS");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e",
                    "POSIX path of (choose folder with prompt \"选择项目文件夹\")");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                // User cancelled the dialog
                return Map.of("path", "");
            }
            // Remove trailing slash if present
            if (output.endsWith("/") && output.length() > 1) {
                output = output.substring(0, output.length() - 1);
            }
            return Map.of("path", output);
        } catch (IOException | InterruptedException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to open directory picker", e);
        }
    }

    /**
     * List directory contents (files + subdirectories).
     */
    @GetMapping("/files")
    public List<FileEntry> listFiles(@RequestParam(defaultValue = "") String path,
                                     @RequestParam(required = false) String workspaceId) {
        Path wd = workingDir(workspaceId);
        Path dir = wd.resolve(path.isBlank() ? "" : path).normalize();

        if (!dir.startsWith(wd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found: " + path);
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> !isHiddenBuildDir(p))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> {
                        String relative = wd.relativize(p).toString();
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
     * Read file content (limit 5MB, returns 413 if exceeded).
     */
    @GetMapping("/files/content")
    public FileContentResponse getFileContent(@RequestParam String path,
                                              @RequestParam(required = false) String workspaceId) {
        Path resolved = resolvePath(path, workspaceId);

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
                    "File too large: " + (size / 1024) + "KB exceeds " + (MAX_FILE_SIZE / 1024 / 1024) + "MB limit");
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
            @RequestParam(required = false) String workspaceId,
            @RequestBody String content) {
        Path resolved = resolvePath(path, workspaceId);

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
    public ResponseEntity<Void> deleteFile(@RequestParam String path,
                                           @RequestParam(required = false) String workspaceId) {
        Path resolved = resolvePath(path, workspaceId);
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
    public Map<String, String> renameFile(@RequestParam String from, @RequestParam String to,
                                          @RequestParam(required = false) String workspaceId) {
        Path src = resolvePath(from, workspaceId);
        Path dst = resolvePath(to, workspaceId);
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
    public Map<String, String> mkdir(@RequestParam String path,
                                     @RequestParam(required = false) String workspaceId) {
        Path resolved = resolvePath(path, workspaceId);
        try {
            Files.createDirectories(resolved);
            return Map.of("path", path, "status", "ok");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create directory", e);
        }
    }

    private Path resolvePath(String path, String workspaceId) {
        if (path.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }
        Path wd = workingDir(workspaceId);
        Path target = wd.resolve(path).normalize();
        if (!target.startsWith(wd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal not allowed");
        }
        return target;
    }

    private ServerConfigResponse buildConfigResponse() {
        return new ServerConfigResponse(
                serverProperties.provider(),
                serverProperties.model(),
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

    private static final java.util.Set<String> HIDDEN_DIRS = java.util.Set.of(
            ".git", ".idea", ".vscode", ".mvn", ".gradle", ".aone",
            ".omc", ".kiro", "node_modules", "target", "build", "dist",
            "__pycache__", ".pytest_cache", ".next", ".nuxt", ".DS_Store");

    private static boolean isHiddenBuildDir(Path p) {
        String name = p.getFileName().toString();
        return HIDDEN_DIRS.contains(name);
    }
}
