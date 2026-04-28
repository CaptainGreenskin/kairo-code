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
 * Loads skills from filesystem directories (global and project-level).
 *
 * <p>Scans {@code *.md} files in the given directories, parses them via
 * {@link SkillMarkdownParser}, and returns the resulting {@link SkillDefinition}s
 * annotated with their source tag.
 *
 * <p>Directory priority (highest first): project &gt; global &gt; classpath.
 * Callers merge in that order — this loader handles one priority level at a time.
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
     * Load all filesystem skills. Project skills override global skills by name.
     *
     * @return list of skills with source tags, project-first order
     */
    public List<SkillWithSource> loadAll() {
        Map<String, SkillWithSource> byName = new LinkedHashMap<>();

        // Global skills (lower priority)
        if (globalSkillsDir != null && Files.isDirectory(globalSkillsDir)) {
            loadDir(globalSkillsDir, "global", byName);
        }

        // Project skills (higher priority — override global by name)
        if (projectSkillsDir != null && Files.isDirectory(projectSkillsDir)) {
            loadDir(projectSkillsDir, "project", byName);
        }

        return new ArrayList<>(byName.values());
    }

    private void loadDir(Path dir, String source, Map<String, SkillWithSource> out) {
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> parseAndRegister(p, source, out));
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", dir, e.getMessage());
        }
    }

    private void parseAndRegister(Path file, String source, Map<String, SkillWithSource> out) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            SkillDefinition skill = parser.parse(content);
            out.put(skill.name(), new SkillWithSource(skill, source));
            log.debug("Loaded {} skill '{}' from {}", source, skill.name(), file);
        } catch (Exception e) {
            log.warn("Failed to parse skill file {}: {}", file, e.getMessage());
        }
    }

    /**
     * A skill definition paired with its source tag.
     */
    public record SkillWithSource(SkillDefinition skill, String source) {}
}
