package io.kairo.code.core.skill;

import io.kairo.api.skill.SkillDefinition;
import io.kairo.skill.SkillMarkdownParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads skills from filesystem directories with 6-level priority support.
 *
 * <p>Scans {@code *.md} files in order of ascending priority so that higher-priority
 * skills override lower-priority ones by name. Current active levels (lowest first):
 * USER ({@code ~/.kairo-code/skills/}), MANAGED ({@code ~/.kairo-code/skills/managed/}),
 * PROJECT ({@code <workingDir>/.kairo-code/skills/}).
 *
 * <p>Reserved levels (not yet loaded): MCP, BUNDLED, PLUGIN.
 */
public class FsSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(FsSkillLoader.class);

    private final Path globalSkillsDir;
    private final Path projectSkillsDir;
    private final SkillMarkdownParser parser;

    /**
     * @param globalSkillsDir  {@code ~/.kairo-code/skills/}, may not exist
     * @param projectSkillsDir {@code <workingDir>/.kairo-code/skills/}, may not exist
     */
    public FsSkillLoader(Path globalSkillsDir, Path projectSkillsDir) {
        this.globalSkillsDir = globalSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
        this.parser = new SkillMarkdownParser();
    }

    /**
     * Load all filesystem skills in priority order. Higher priority overrides lower by name.
     *
     * <p>Scan order (ascending priority): USER → MANAGED → PROJECT.
     *
     * @return list of merged skills with source tags
     */
    public List<SkillWithSource> loadAll() {
        Map<String, SkillWithSource> byName = new LinkedHashMap<>();

        // Priority 5: USER — global skills, skip managed/ subdir
        if (globalSkillsDir != null && Files.isDirectory(globalSkillsDir)) {
            loadDir(globalSkillsDir, SkillPriority.USER, byName, true);
        }

        // Priority 6: MANAGED — admin-managed skills
        if (globalSkillsDir != null) {
            Path managedDir = globalSkillsDir.resolve("managed");
            if (Files.isDirectory(managedDir)) {
                loadDir(managedDir, SkillPriority.MANAGED, byName, false);
            }
        }

        // Priority 4: PROJECT — project-level skills (overrides global)
        if (projectSkillsDir != null && Files.isDirectory(projectSkillsDir)) {
            loadDir(projectSkillsDir, SkillPriority.PROJECT, byName, false);
        }

        return new ArrayList<>(byName.values());
    }

    /**
     * Return the directories that should be watched for hot-reload.
     *
     * @return list of paths to register with WatchService
     */
    public List<Path> getWatchedDirs() {
        List<Path> dirs = new ArrayList<>();
        if (globalSkillsDir != null) {
            dirs.add(globalSkillsDir);
            dirs.add(globalSkillsDir.resolve("managed"));
        }
        if (projectSkillsDir != null) {
            dirs.add(projectSkillsDir);
        }
        return dirs;
    }

    private void loadDir(Path dir, SkillPriority priority,
                         Map<String, SkillWithSource> out, boolean skipManaged) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> skipManaged ? !p.getFileName().toString().equals("managed") : true)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> parseAndRegister(p, priority, out));
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", dir, e.getMessage());
        }
    }

    private void parseAndRegister(Path file, SkillPriority priority,
                                  Map<String, SkillWithSource> out) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            SkillDefinition skill = parser.parse(content);
            out.put(skill.name(), new SkillWithSource(skill, priority));
            log.debug("Loaded {} skill '{}' from {}", priority.name(), skill.name(), file);
        } catch (Exception e) {
            log.warn("Failed to parse skill file {}: {}", file, e.getMessage());
        }
    }

    /**
     * A skill definition paired with its priority source.
     */
    public record SkillWithSource(SkillDefinition skill, SkillPriority priority) {}
}
