package io.kairo.code.core.skill;

import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.mcp.McpClientRegistry;
import io.kairo.skill.SkillMarkdownParser;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges MCP server {@code skill://} resources into the Kairo skill registry.
 *
 * <p>After MCP servers connect, this scans each server's resources for URIs starting with
 * {@code skill://}, reads their content (expected to be Markdown with YAML frontmatter),
 * and registers them as skills with {@code mcp__<server>__<name>} naming convention.
 */
public final class McpSkillBridge {

    private static final Logger log = LoggerFactory.getLogger(McpSkillBridge.class);
    private static final String SKILL_URI_PREFIX = "skill://";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final SkillMarkdownParser parser = new SkillMarkdownParser();

    public List<SkillDefinition> bridge(McpClientRegistry mcpRegistry, SkillRegistry skillRegistry) {
        List<SkillDefinition> discovered = new ArrayList<>();

        for (String serverName : mcpRegistry.getServerNames()) {
            try {
                McpAsyncClient client = mcpRegistry.getAsyncClient(serverName);
                if (client == null) continue;

                McpSchema.ListResourcesResult resourcesResult;
                try {
                    resourcesResult = client.listResources().block(TIMEOUT);
                } catch (Exception e) {
                    log.debug("MCP server '{}' does not support resources: {}", serverName, e.getMessage());
                    continue;
                }

                if (resourcesResult == null || resourcesResult.resources() == null) continue;

                for (McpSchema.Resource resource : resourcesResult.resources()) {
                    String uri = resource.uri();
                    if (uri == null || !uri.startsWith(SKILL_URI_PREFIX)) continue;

                    try {
                        McpSchema.ReadResourceResult readResult = client.readResource(
                                new McpSchema.ReadResourceRequest(uri)).block(TIMEOUT);
                        if (readResult == null || readResult.contents() == null
                                || readResult.contents().isEmpty()) continue;

                        String content = null;
                        for (McpSchema.ResourceContents rc : readResult.contents()) {
                            if (rc instanceof McpSchema.TextResourceContents textRes) {
                                content = textRes.text();
                                break;
                            }
                        }
                        if (content == null || content.isBlank()) continue;

                        String resourceName = uri.substring(SKILL_URI_PREFIX.length());
                        String skillName = "mcp__" + normalizeName(serverName)
                                + "__" + normalizeName(resourceName);

                        SkillDefinition skill = parser.parse(content);
                        SkillDefinition named = new SkillDefinition(
                                skillName, skill.version(), skill.description(),
                                skill.instructions(), skill.triggerConditions(),
                                skill.category(), skill.pathPatterns(),
                                skill.requiredTools(), skill.platform(),
                                skill.matchScore(), skill.allowedTools());

                        skillRegistry.register(named);
                        discovered.add(named);
                        log.info("MCP skill '{}' registered from server '{}'", skillName, serverName);
                    } catch (Exception e) {
                        log.warn("Failed to load MCP skill from '{}' resource '{}': {}",
                                serverName, uri, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to scan MCP server '{}' for skills: {}", serverName, e.getMessage());
            }
        }

        if (!discovered.isEmpty()) {
            log.info("Registered {} MCP skill(s)", discovered.size());
        }
        return discovered;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_");
    }
}
