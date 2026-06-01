package io.kairo.code.core.plugin;

import io.kairo.api.plugin.PluginComponent;
import io.kairo.api.plugin.PluginComponent.CommandComponent;
import io.kairo.api.plugin.PluginComponent.HookComponent;
import io.kairo.api.plugin.PluginComponent.McpComponent;
import io.kairo.api.plugin.PluginComponent.SkillComponent;
import io.kairo.plugin.ComponentRegistrar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Real implementation of {@link ComponentRegistrar} that routes plugin components to
 * the appropriate kairo-code registries. Replaces the previous {@code noOp()} stub.
 *
 * <p>Component routing:
 * <ul>
 *   <li>{@link SkillComponent} -- already handled via directory side-channel
 *       ({@link PluginManagerFactory#enabledSkillDirs}), so this registrar logs but skips.
 *   <li>{@link CommandComponent} -- treated as a skill (markdown commands are skills with a
 *       slash-command trigger). Logged for future wiring.
 *   <li>{@link HookComponent} -- would need integration with the shell-hook executor
 *       (HooksConfig/HookExecutor). Logged for future wiring.
 *   <li>{@link McpComponent} -- would need merging into McpConfig. Logged for future wiring.
 *   <li>Other types (ToolComponent, AgentComponent, etc.) -- logged as unsupported.
 * </ul>
 *
 * <p>This registrar tracks registered components per plugin for {@link #registeredCount}
 * and {@link #unregisterAll} bookkeeping, even when the actual wiring is a log-only stub.
 * This lets the PluginManager's enable/disable lifecycle work correctly.
 */
public final class KairoComponentRegistrar implements ComponentRegistrar {

    private static final Logger log = LoggerFactory.getLogger(KairoComponentRegistrar.class);

    private final Map<String, List<PluginComponent>> registered = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> registerAll(String pluginId, List<PluginComponent> components) {
        return Mono.fromRunnable(() -> {
            registered.put(pluginId, List.copyOf(components));
            for (PluginComponent c : components) {
                if (c instanceof SkillComponent s) {
                    log.debug("Plugin '{}': skill '{}' (loaded via directory side-channel)", pluginId, s.name());
                } else if (c instanceof CommandComponent cmd) {
                    log.info("Plugin '{}': registered command '{}' from {}", pluginId, cmd.name(), cmd.commandFile());
                } else if (c instanceof HookComponent hook) {
                    log.info("Plugin '{}': registered hook on event '{}' ({} actions)", pluginId, hook.event(), hook.actions().size());
                } else if (c instanceof McpComponent mcp) {
                    log.info("Plugin '{}': registered MCP server '{}' ({})", pluginId, mcp.serverName(), mcp.command());
                } else {
                    log.debug("Plugin '{}': unsupported component type {}", pluginId, c.getClass().getSimpleName());
                }
            }
            int count = components.size();
            if (count > 0) {
                log.info("Plugin '{}': {} component(s) registered", pluginId, count);
            }
        });
    }

    @Override
    public Mono<Void> unregisterAll(String pluginId) {
        return Mono.fromRunnable(() -> {
            List<PluginComponent> removed = registered.remove(pluginId);
            if (removed != null && !removed.isEmpty()) {
                log.info("Plugin '{}': {} component(s) unregistered", pluginId, removed.size());
            }
        });
    }

    @Override
    public int registeredCount(String pluginId) {
        List<PluginComponent> list = registered.get(pluginId);
        return list != null ? list.size() : 0;
    }
}
