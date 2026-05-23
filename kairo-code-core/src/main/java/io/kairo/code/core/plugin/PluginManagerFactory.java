/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.plugin;

import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginManager;
import io.kairo.plugin.ComponentRegistrar;
import io.kairo.plugin.DefaultPluginManager;
import io.kairo.plugin.DefaultPluginRegistry;
import io.kairo.plugin.PluginLoader;
import io.kairo.plugin.installer.PluginCacheManager;
import io.kairo.plugin.source.GitHubSourceFetcher;
import io.kairo.plugin.source.GitSubdirSourceFetcher;
import io.kairo.plugin.source.GitUrlSourceFetcher;
import io.kairo.plugin.source.HttpDownloader;
import io.kairo.plugin.source.LocalPathSourceFetcher;
import io.kairo.plugin.source.NpmSourceFetcher;
import io.kairo.plugin.source.SourceFetcherRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a fully-wired upstream {@link PluginManager} for kairo-code. Replaces the original
 * self-built {@code PluginRegistry} (deleted in M-C2) — gains remote install via GitHub / NPM /
 * Git / GitSubdir + Claude-Code-compatible {@code plugin.json} format + atomic component
 * registration.
 *
 * <p>Layout produced under {@code <kairoDir>/plugins/}:
 *
 * <pre>
 *   .kairo-code/plugins/
 *     cache/    ← extracted source artifacts keyed by sha8 (managed by PluginCacheManager)
 *     data/     ← per-plugin runtime data dirs
 * </pre>
 */
public final class PluginManagerFactory {

    private PluginManagerFactory() {}

    /**
     * Build a {@link PluginManager} rooted under {@code kairoDir}. Component binding is currently
     * a no-op — kairo-code reads enabled plugins' skill directories directly via {@link
     * #enabledSkillDirs(PluginManager)} and feeds them to {@code FsSkillLoader}. Hook / MCP /
     * command bridges are follow-on work.
     */
    public static PluginManager create(Path kairoDir) throws IOException {
        Path pluginsRoot = kairoDir.resolve("plugins");
        Path cacheRoot = pluginsRoot.resolve("cache");
        Path dataRoot = pluginsRoot.resolve("data");
        Files.createDirectories(cacheRoot);
        Files.createDirectories(dataRoot);

        PluginCacheManager cache = new PluginCacheManager(cacheRoot);
        HttpDownloader http = HttpDownloader.jdk();

        SourceFetcherRegistry fetchers =
                new SourceFetcherRegistry()
                        .register(new LocalPathSourceFetcher())
                        .register(new GitHubSourceFetcher(cache, http))
                        .register(new NpmSourceFetcher(cache, http))
                        .register(new GitUrlSourceFetcher(cache))
                        .register(new GitSubdirSourceFetcher(cache));

        return new DefaultPluginManager(
                new DefaultPluginRegistry(),
                new PluginLoader(),
                dataRoot,
                ComponentRegistrar.noOp(),
                fetchers);
    }

    /**
     * Walk every enabled installation and return its {@code <root>/skills} directory if it
     * exists. {@code FsSkillLoader} consumes this list to load plugin-contributed markdown
     * skills into the parent skill registry.
     */
    public static List<Path> enabledSkillDirs(PluginManager manager) {
        if (manager == null) return List.of();
        return manager.list().stream()
                .filter(PluginInstallation::enabled)
                .map(p -> p.rootPath().resolve("skills"))
                .filter(Files::isDirectory)
                .collect(Collectors.toUnmodifiableList());
    }
}
