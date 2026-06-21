package io.kairo.code.core.skill;

import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillMetadata;
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
 * <p>Scans {@code *.md} files (flat form) and {@code <name>/SKILL.md} subdirectories (bundle form,
 * which may also contain {@code scripts/}, {@code references/}, etc.) in order of ascending priority
 * so that higher-priority skills override lower-priority ones by name. Current active levels
 * (lowest first): USER ({@code ~/.kairo-code/skills/}), MANAGED ({@code ~/.kairo-code/skills/managed/}),
 * PROJECT ({@code <workingDir>/.kairo-code/skills/}).
 *
 * <p>Reserved levels (not yet loaded): MCP, BUNDLED, PLUGIN.
 *
 * <p>Each skill is parsed with full metadata including visibility and
 * model-invocation control via {@link SkillMetadata}.
 */
public class FsSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(FsSkillLoader.class);

    private final Path globalSkillsDir;
    private final Path projectSkillsDir;
    private final SkillMarkdownParser parser;
    private final List<Path> pluginSkillDirs;

    /**
     * @param globalSkillsDir  {@code ~/.kairo-code/skills/}, may not exist
     * @param projectSkillsDir {@code <workingDir>/.kairo-code/skills/}, may not exist
     */
    public FsSkillLoader(Path globalSkillsDir, Path projectSkillsDir) {
        this(globalSkillsDir, projectSkillsDir, List.of());
    }

    /**
     * @param globalSkillsDir  {@code ~/.kairo-code/skills/}, may not exist
     * @param projectSkillsDir {@code <workingDir>/.kairo-code/skills/}, may not exist
     * @param pluginSkillDirs  skill directories from enabled plugins
     */
    public FsSkillLoader(Path globalSkillsDir, Path projectSkillsDir, List<Path> pluginSkillDirs) {
        this.globalSkillsDir = globalSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
        this.pluginSkillDirs = pluginSkillDirs != null ? pluginSkillDirs : List.of();
        this.parser = new SkillMarkdownParser();
    }

    /**
     * Load all filesystem skills in priority order. Higher priority overrides lower by name.
     *
     * <p>Scan order (ascending priority): PLUGIN -> USER -> MANAGED -> PROJECT.
     *
     * @return list of merged skills with source tags
     */
    public List<SkillWithSource> loadAll() {
        Map<String, SkillWithSource> byName = new LinkedHashMap<>();

        // Priority 3: PLUGIN — skills from enabled plugins
        for (Path pluginDir : pluginSkillDirs) {
            if (Files.isDirectory(pluginDir)) {
                loadDir(pluginDir, SkillPriority.PLUGIN, byName, false);
            }
        }

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
            stream.forEach(p -> {
                String fileName = p.getFileName().toString();
                // USER-level scan skips the reserved managed/ subdir — it is loaded
                // separately under MANAGED priority.
                if (skipManaged && fileName.equals("managed")) {
                    return;
                }
                if (Files.isRegularFile(p) && fileName.endsWith(".md")) {
                    parseAndRegister(p, priority, out);
                    return;
                }
                // Bundle form: <dir>/<bundle-name>/SKILL.md (with optional scripts/, references/)
                if (Files.isDirectory(p)) {
                    Path skillMd = p.resolve("SKILL.md");
                    if (Files.isRegularFile(skillMd)) {
                        parseAndRegister(skillMd, priority, out);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", dir, e.getMessage());
        }
    }

    private void parseAndRegister(Path file, SkillPriority priority,
                                  Map<String, SkillWithSource> out) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            SkillMetadata metadata = parser.parseWithMetadata(content);
            out.put(metadata.name(), new SkillWithSource(metadata, priority));
            log.debug("Loaded {} skill '{}' (visibility={}) from {}",
                    priority.name(), metadata.name(),
                    metadata.visibility(), file);
        } catch (Exception e) {
            log.warn("Failed to parse skill file {}: {}", file, e.getMessage());
        }
    }

    /**
     * A skill metadata paired with its priority source.
     */
    public record SkillWithSource(SkillMetadata metadata, SkillPriority priority) {

        /** Shorthand access to the underlying SkillDefinition. */
        public SkillDefinition skill() {
            return metadata.definition();
        }
    }
}
