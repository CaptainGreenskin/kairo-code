package io.kairo.code.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists agent results for workflow resume support.
 *
 * <p>Each workflow run produces a journal file at {@code .kairo/workflow-runs/<runId>.json}.
 * Agent results are cached by a key derived from {@code sha256(prompt + opts)}.
 * On resume, unchanged agent calls return cached results instantly.
 */
public final class WorkflowRunJournal {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String RUNS_DIR = ".kairo/workflow-runs";

    private final String runId;
    private final Path journalPath;
    private final Map<String, Object> cache;
    private final Instant startedAt;
    private volatile String workflowName;

    private WorkflowRunJournal(String runId, Path journalPath, Map<String, Object> cache,
                               Instant startedAt, String workflowName) {
        this.runId = runId;
        this.journalPath = journalPath;
        this.cache = new ConcurrentHashMap<>(cache);
        this.startedAt = startedAt;
        this.workflowName = workflowName;
    }

    public static WorkflowRunJournal create(Path workspaceRoot) {
        String runId = "wf_" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = workspaceRoot.resolve(RUNS_DIR);
        Path journalPath = dir.resolve(runId + ".json");
        return new WorkflowRunJournal(runId, journalPath, Map.of(), Instant.now(), null);
    }

    @SuppressWarnings("unchecked")
    public static WorkflowRunJournal resume(String runId, Path workspaceRoot) throws IOException {
        Path dir = workspaceRoot.resolve(RUNS_DIR);
        Path journalPath = dir.resolve(runId + ".json");
        if (!Files.isRegularFile(journalPath)) {
            throw new IOException("Journal not found for runId: " + runId + " at " + journalPath);
        }
        Map<String, Object> data = MAPPER.readValue(journalPath.toFile(), Map.class);
        Map<String, Object> entries = (Map<String, Object>) data.getOrDefault("entries", Map.of());
        Instant started = data.containsKey("startedAt")
                ? Instant.parse(data.get("startedAt").toString()) : Instant.now();
        String name = data.containsKey("workflowName")
                ? data.get("workflowName").toString() : null;
        return new WorkflowRunJournal(runId, journalPath, new LinkedHashMap<>(entries), started, name);
    }

    public String runId() { return runId; }

    public void setWorkflowName(String name) { this.workflowName = name; }

    public Optional<Object> getCached(String cacheKey) {
        return Optional.ofNullable(cache.get(cacheKey));
    }

    public void cache(String cacheKey, Object result) {
        cache.put(cacheKey, result);
    }

    public void save() throws IOException {
        Files.createDirectories(journalPath.getParent());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        if (workflowName != null) data.put("workflowName", workflowName);
        data.put("startedAt", startedAt.toString());
        data.put("savedAt", Instant.now().toString());
        data.put("entryCount", cache.size());
        data.put("entries", cache);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(journalPath.toFile(), data);
    }

    /** Lists all workflow runs from disk, sorted by most recent first. */
    @SuppressWarnings("unchecked")
    public static List<RunSummary> listRuns(Path workspaceRoot) {
        Path dir = workspaceRoot.resolve(RUNS_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        List<RunSummary> runs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "wf_*.json")) {
            for (Path file : stream) {
                try {
                    Map<String, Object> data = MAPPER.readValue(file.toFile(), Map.class);
                    String runId = (String) data.get("runId");
                    String name = (String) data.getOrDefault("workflowName", "—");
                    String savedAt = (String) data.getOrDefault("savedAt", "");
                    int entries = data.containsKey("entryCount")
                            ? ((Number) data.get("entryCount")).intValue()
                            : ((Map<?, ?>) data.getOrDefault("entries", Map.of())).size();
                    runs.add(new RunSummary(runId, name, savedAt, entries));
                } catch (Exception e) {
                    // skip corrupt files
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        runs.sort(Comparator.comparing(RunSummary::savedAt).reversed());
        return runs;
    }

    public record RunSummary(String runId, String workflowName, String savedAt, int entryCount) {}

    public static String computeCacheKey(String prompt, Map<String, Object> opts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (opts != null && !opts.isEmpty()) {
                String optsJson = MAPPER.writeValueAsString(opts);
                md.update(optsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to compute cache key: " + e.getMessage(), e);
        }
    }
}
