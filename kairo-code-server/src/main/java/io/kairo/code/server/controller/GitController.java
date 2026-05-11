package io.kairo.code.server.controller;

import io.kairo.code.server.config.WorkspaceConfig;
import io.kairo.code.server.config.WorkspacePersistenceService;
import io.kairo.code.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST controller exposing git status / diff for the configured working directory.
 */
@RestController
@RequestMapping("/api/git")
public class GitController {

    private static final int MAX_DIFF_LINES = 500;
    private static final int GIT_TIMEOUT_SECONDS = 10;

    private final AgentService agentService;
    private final WorkspacePersistenceService workspaceService;

    public GitController(AgentService agentService, WorkspacePersistenceService workspaceService) {
        this.agentService = agentService;
        this.workspaceService = workspaceService;
    }

    /**
     * Resolve the working directory for git ops. After M112 the source of truth is the
     * client-provided {@code workspaceId} — fall back to the server-bootstrap default
     * only if the caller didn't pass one (e.g. legacy clients).
     */
    private Path resolveWorkingDir(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            WorkspaceConfig ws = workspaceService.findById(workspaceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Workspace not found: " + workspaceId));
            String dir = ws.workingDir();
            if (dir == null || dir.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Workspace has no working directory");
            }
            return Paths.get(dir);
        }
        String dir = agentService.getDefaultWorkingDir();
        if (dir == null || dir.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Working directory not configured");
        }
        return Paths.get(dir);
    }

    /**
     * Returns git status output (modified/untracked files).
     * Format: list of {path, status} where status is one of:
     *   M=modified, A=added, D=deleted, ??=untracked
     */
    @GetMapping("/status")
    public List<Map<String, String>> getStatus(
            @RequestParam(required = false) String workspaceId) {
        String output = runGit(resolveWorkingDir(workspaceId), "git", "status", "--porcelain");
        List<Map<String, String>> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank() || line.length() < 3) continue;
            String status = line.substring(0, 2).trim();
            String path = line.substring(3).trim();
            if (status.isEmpty()) status = line.substring(0, 2);
            result.add(Map.of("status", status, "path", path));
        }
        return result;
    }

    /**
     * Returns git diff for a specific file (or all if path is blank).
     * Limits output to {@value #MAX_DIFF_LINES} lines to prevent UI overload.
     *
     * <p>For untracked files (which {@code git diff HEAD} ignores), set {@code untracked=true}
     * so the controller falls back to {@code git diff --no-index /dev/null <path>} — that
     * synthesizes a full-add diff against an empty file so the UI still gets meaningful output.
     */
    @GetMapping("/diff")
    public Map<String, String> getDiff(@RequestParam(defaultValue = "") String path,
                                       @RequestParam(defaultValue = "false") boolean untracked,
                                       @RequestParam(required = false) String workspaceId) {
        Path workingDir = resolveWorkingDir(workspaceId);
        String[] cmd;
        if (untracked && !path.isBlank()) {
            // /dev/null is portable on macOS/Linux; on Windows use NUL — we target POSIX backends only.
            // --no-index returns exit 1 on differences, which runGit treats as non-fatal.
            cmd = new String[]{"git", "diff", "--no-index", "--", "/dev/null", path};
        } else if (path.isBlank()) {
            cmd = new String[]{"git", "diff", "HEAD"};
        } else {
            cmd = new String[]{"git", "diff", "HEAD", "--", path};
        }
        String output = runGit(workingDir, cmd);
        String[] lines = output.split("\n");
        boolean truncated = lines.length > MAX_DIFF_LINES;
        String content = truncated
                ? String.join("\n", Arrays.copyOf(lines, MAX_DIFF_LINES)) + "\n... (truncated)"
                : output;
        return Map.of("diff", content, "truncated", String.valueOf(truncated));
    }

    /**
     * Returns the current branch name (or empty if detached HEAD / not a git repo).
     */
    @GetMapping("/branch")
    public Map<String, String> getBranch(@RequestParam(required = false) String workspaceId) {
        Path workingDir = resolveWorkingDir(workspaceId);
        String name = runGit(workingDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        if ("HEAD".equals(name)) {
            // Detached: surface short SHA so user knows where they are.
            String sha = runGit(workingDir, "git", "rev-parse", "--short", "HEAD").trim();
            return Map.of("name", "", "detachedSha", sha);
        }
        return Map.of("name", name, "detachedSha", "");
    }

    /**
     * Recent commits (oldest of the {@code limit} returned last).
     * Each entry: {@code {sha, shortSha, subject, author, relDate}}.
     */
    @GetMapping("/log")
    public List<Map<String, String>> getLog(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String workspaceId) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Path workingDir = resolveWorkingDir(workspaceId);
        // Use a safe delimiter (\u001f = unit separator) — commit subjects can contain anything else.
        String fmt = "%H%x1f%h%x1f%s%x1f%an%x1f%cr";
        String output = runGit(workingDir,
                "git", "log", "--max-count=" + safeLimit, "--pretty=format:" + fmt);
        List<Map<String, String>> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\u001f", -1);
            if (parts.length < 5) continue;
            result.add(Map.of(
                    "sha", parts[0],
                    "shortSha", parts[1],
                    "subject", parts[2],
                    "author", parts[3],
                    "relDate", parts[4]));
        }
        return result;
    }

    /**
     * Discard local changes for the given file(s). Tracked files are restored from HEAD;
     * untracked files are deleted. Body: {@code {paths: ["a.java"], untracked: ["b.tmp"]}}.
     * Both lists are optional but at least one must be non-empty.
     */
    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody Map<String, Object> body,
                                       @RequestParam(required = false) String workspaceId) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body is required");
        }
        @SuppressWarnings("unchecked")
        List<String> tracked = (List<String>) body.get("paths");
        @SuppressWarnings("unchecked")
        List<String> untracked = (List<String>) body.get("untracked");
        boolean hasTracked = tracked != null && !tracked.isEmpty();
        boolean hasUntracked = untracked != null && !untracked.isEmpty();
        if (!hasTracked && !hasUntracked) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one of paths/untracked must be non-empty");
        }
        Path workingDir = resolveWorkingDir(workspaceId);
        if (hasTracked) {
            List<String> args = new ArrayList<>(tracked.size() + 4);
            args.add("git");
            args.add("checkout");
            args.add("HEAD");
            args.add("--");
            args.addAll(tracked);
            runGitChecked(workingDir, args.toArray(new String[0]));
        }
        if (hasUntracked) {
            // -f forces removal of files not under version control; constrained to the explicit paths.
            List<String> args = new ArrayList<>(untracked.size() + 3);
            args.add("git");
            args.add("clean");
            args.add("-f");
            args.add("--");
            args.addAll(untracked);
            runGitChecked(workingDir, args.toArray(new String[0]));
        }
        return Map.of("ok", true);
    }

    /**
     * Stages files. Body: {@code {paths: ["a.java","b.java"]}}. Empty/absent paths → stage all (-A).
     */
    @PostMapping("/stage")
    public Map<String, Object> stage(@RequestBody(required = false) Map<String, Object> body,
                                     @RequestParam(required = false) String workspaceId) {
        Path workingDir = resolveWorkingDir(workspaceId);
        @SuppressWarnings("unchecked")
        List<String> paths = body == null ? null : (List<String>) body.get("paths");
        String[] cmd;
        if (paths == null || paths.isEmpty()) {
            cmd = new String[]{"git", "add", "-A"};
        } else {
            List<String> args = new ArrayList<>(paths.size() + 2);
            args.add("git");
            args.add("add");
            args.add("--");
            args.addAll(paths);
            cmd = args.toArray(new String[0]);
        }
        runGitChecked(workingDir, cmd);
        return Map.of("ok", true);
    }

    /**
     * Commits staged changes. Body: {@code {message: "..."}}. Returns commit summary or error.
     */
    @PostMapping("/commit")
    public Map<String, Object> commit(@RequestBody Map<String, Object> body,
                                      @RequestParam(required = false) String workspaceId) {
        String message = body == null ? null : (String) body.get("message");
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commit message is required");
        }
        Path workingDir = resolveWorkingDir(workspaceId);
        Object stageAllRaw = body.get("stageAll");
        boolean stageAll = stageAllRaw instanceof Boolean ? (Boolean) stageAllRaw : false;
        if (stageAll) {
            runGitChecked(workingDir, "git", "add", "-A");
        }
        String output = runGitChecked(workingDir, "git", "commit", "-m", message);
        return Map.of("ok", true, "output", output);
    }

    private String runGitChecked(Path workingDir, String... cmd) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            proc = pb.start();
            boolean finished = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Git command timed out");
            }
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Git command failed (exit " + exitCode + "): " + output.trim());
            }
            return output;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroyForcibly();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Git command interrupted");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Git command failed: " + e.getMessage());
        }
    }

    private String runGit(Path workingDir, String... cmd) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir.toFile());
            // Do NOT merge stderr into stdout — git errors on stderr must not
            // leak into the porcelain status output.
            proc = pb.start();

            // Drain stderr asynchronously so the OS buffer doesn't fill and block.
            final Process finalProc = proc;
            Thread stderrDrain = new Thread(() -> {
                try { finalProc.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            });
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            boolean finished = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Git command timed out");
            }
            stderrDrain.join(1000);
            // Read stdout BEFORE branching on exit code — `git diff --no-index` returns
            // exit 1 when files differ (which is the success case for our untracked-file
            // diff). Treat exit ∈ {0, 1} as "stdout is meaningful"; only severe failures
            // (e.g. exit 128 = not a git repo) collapse to empty.
            byte[] stdout = proc.getInputStream().readAllBytes();
            int exitCode = proc.exitValue();
            if (exitCode > 1) {
                return "";
            }
            return new String(stdout);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroyForcibly();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Git command interrupted");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Git command failed: " + e.getMessage());
        }
    }
}
