package io.kairo.code.core.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs skills from git repositories into {@code ~/.kairo-code/skills/managed/}.
 *
 * <p>Supports full git URLs and GitHub shorthand ({@code owner/repo}).
 */
public final class SkillInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillInstaller.class);

    private final Path managedDir;

    public SkillInstaller() {
        this(Path.of(System.getProperty("user.home"), ".kairo-code", "skills", "managed"));
    }

    SkillInstaller(Path managedDir) {
        this.managedDir = managedDir;
    }

    public record InstalledSkill(String name, String gitUrl, Path path) {}

    public InstalledSkill install(String source) throws IOException, InterruptedException {
        String gitUrl = resolveGitUrl(source);
        String name = extractName(source);
        Path target = managedDir.resolve(name);

        if (Files.exists(target)) {
            throw new IllegalArgumentException("Skill '" + name + "' already installed at " + target);
        }

        Files.createDirectories(managedDir);
        int exitCode = new ProcessBuilder("git", "clone", "--depth", "1", gitUrl, target.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor();

        if (exitCode != 0 || !Files.isDirectory(target)) {
            throw new IOException("git clone failed (exit=" + exitCode + ") for " + gitUrl);
        }

        log.info("Installed skill '{}' from {}", name, gitUrl);
        return new InstalledSkill(name, gitUrl, target);
    }

    public boolean uninstall(String name) throws IOException {
        Path target = managedDir.resolve(name);
        if (!Files.isDirectory(target)) {
            return false;
        }
        deleteRecursively(target);
        log.info("Uninstalled skill '{}'", name);
        return true;
    }

    public boolean update(String name) throws IOException, InterruptedException {
        Path target = managedDir.resolve(name);
        if (!Files.isDirectory(target.resolve(".git"))) {
            return false;
        }
        int exitCode = new ProcessBuilder("git", "-C", target.toString(), "pull", "--ff-only")
                .redirectErrorStream(true)
                .start()
                .waitFor();
        if (exitCode != 0) {
            throw new IOException("git pull failed for skill '" + name + "'");
        }
        log.info("Updated skill '{}'", name);
        return true;
    }

    public List<InstalledSkill> list() {
        List<InstalledSkill> result = new ArrayList<>();
        if (!Files.isDirectory(managedDir)) return result;

        try (Stream<Path> dirs = Files.list(managedDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                String gitUrl = readGitRemote(dir);
                result.add(new InstalledSkill(name, gitUrl, dir));
            });
        } catch (IOException e) {
            log.warn("Failed to list managed skills: {}", e.getMessage());
        }
        return result;
    }

    private static String resolveGitUrl(String source) {
        if (source.startsWith("http://") || source.startsWith("https://")
                || source.startsWith("git@") || source.endsWith(".git")) {
            return source;
        }
        if (source.matches("[\\w.-]+/[\\w.-]+")) {
            return "https://github.com/" + source + ".git";
        }
        throw new IllegalArgumentException(
                "Invalid source: '" + source + "'. Use a git URL or GitHub shorthand (owner/repo)");
    }

    private static String extractName(String source) {
        String cleaned = source.replaceAll("\\.git$", "");
        int lastSlash = cleaned.lastIndexOf('/');
        return lastSlash >= 0 ? cleaned.substring(lastSlash + 1) : cleaned;
    }

    private static String readGitRemote(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "-C", dir.toString(),
                    "config", "--get", "remote.origin.url")
                    .redirectErrorStream(true).start();
            String url = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return url.isEmpty() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
