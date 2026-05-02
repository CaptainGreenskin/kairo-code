package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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

    public GitController(AgentService agentService) {
        this.agentService = agentService;
    }

    private Path getCurrentWorkingDir() {
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
    public List<Map<String, String>> getStatus() {
        String output = runGit(getCurrentWorkingDir(), "git", "status", "--porcelain");
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
     */
    @GetMapping("/diff")
    public Map<String, String> getDiff(@RequestParam(defaultValue = "") String path) {
        Path workingDir = getCurrentWorkingDir();
        String[] cmd = path.isBlank()
                ? new String[]{"git", "diff", "HEAD"}
                : new String[]{"git", "diff", "HEAD", "--", path};
        String output = runGit(workingDir, cmd);
        String[] lines = output.split("\n");
        boolean truncated = lines.length > MAX_DIFF_LINES;
        String content = truncated
                ? String.join("\n", Arrays.copyOf(lines, MAX_DIFF_LINES)) + "\n... (truncated)"
                : output;
        return Map.of("diff", content, "truncated", String.valueOf(truncated));
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
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                // Non-zero exit (e.g. not a git repo, no commits yet) → return empty.
                return "";
            }
            return new String(proc.getInputStream().readAllBytes());
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
