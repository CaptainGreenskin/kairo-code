package io.kairo.code.core.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers, loads, and manages plugins from {@code .kairo-code/plugins/} directories.
 *
 * <p>Plugins are directories containing a {@code plugin.yaml} manifest file.
 * The registry supports both project-local and user-global plugin directories.
 *
 * <p>Layout:
 * <pre>
 * .kairo-code/plugins/
 *   my-plugin/
 *     plugin.yaml
 *     skills/
 *       my-skill.md
 * </pre>
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private static final String PLUGINS_DIR = "plugins";
    private static final String MANIFEST_FILE = "plugin.yaml";

    private final Map<String, LoadedPlugin> plugins = new LinkedHashMap<>();
    private final List<Path> pluginRoots;

    public PluginRegistry(Path projectKairoDir, Path userKairoDir) {
        this.pluginRoots = new ArrayList<>();
        if (userKairoDir != null) {
            pluginRoots.add(userKairoDir.resolve(PLUGINS_DIR));
        }
        if (projectKairoDir != null) {
            pluginRoots.add(projectKairoDir.resolve(PLUGINS_DIR));
        }
    }

    /**
     * Discover and load all plugins from the configured root directories.
     * Project plugins override user plugins with the same name.
     */
    public void discover() {
        plugins.clear();
        for (Path root : pluginRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory)
                        .sorted()
                        .forEach(this::loadPlugin);
            } catch (IOException e) {
                log.warn("Failed to scan plugin directory {}: {}", root, e.getMessage());
            }
        }
        log.info("Discovered {} plugin(s): {}", plugins.size(), plugins.keySet());
    }

    private void loadPlugin(Path pluginDir) {
        Path manifestFile = pluginDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            log.debug("Skipping {} — no plugin.yaml", pluginDir);
            return;
        }
        try {
            PluginManifest manifest = PluginManifest.fromYaml(manifestFile);
            plugins.put(manifest.name(), new LoadedPlugin(manifest, pluginDir));
        } catch (Exception e) {
            log.warn("Failed to load plugin from {}: {}", pluginDir, e.getMessage());
        }
    }

    /**
     * List all discovered plugins.
     */
    public List<LoadedPlugin> list() {
        return Collections.unmodifiableList(new ArrayList<>(plugins.values()));
    }

    /**
     * Get a specific plugin by name.
     */
    public Optional<LoadedPlugin> get(String name) {
        return Optional.ofNullable(plugins.get(name));
    }

    /**
     * Enable a plugin by name. Returns true if the plugin was found.
     */
    public boolean enable(String name) {
        return setEnabled(name, true);
    }

    /**
     * Disable a plugin by name. Returns true if the plugin was found.
     */
    public boolean disable(String name) {
        return setEnabled(name, false);
    }

    private boolean setEnabled(String name, boolean enabled) {
        LoadedPlugin plugin = plugins.get(name);
        if (plugin == null) {
            return false;
        }
        plugins.put(name, new LoadedPlugin(plugin.manifest().withEnabled(enabled), plugin.directory()));
        return true;
    }

    /**
     * Return all enabled plugins' skill directories for FsSkillLoader to scan.
     */
    public List<Path> enabledSkillDirs() {
        List<Path> dirs = new ArrayList<>();
        for (LoadedPlugin plugin : plugins.values()) {
            if (!plugin.manifest().enabled()) {
                continue;
            }
            Path skillsDir = plugin.directory().resolve("skills");
            if (Files.isDirectory(skillsDir)) {
                dirs.add(skillsDir);
            }
        }
        return dirs;
    }

    /**
     * A loaded plugin pairing its manifest with its filesystem location.
     */
    public record LoadedPlugin(PluginManifest manifest, Path directory) {
        public String name() {
            return manifest.name();
        }

        public boolean enabled() {
            return manifest.enabled();
        }
    }
}
