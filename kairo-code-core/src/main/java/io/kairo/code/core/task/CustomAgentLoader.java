package io.kairo.code.core.task;

import io.kairo.api.agent.SubagentDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads custom agent definitions from {@code .kairo/agents/*.md} files.
 * <p>
 * File format (mirrors Claude Code's {@code .claude/agents/*.md}):
 * <pre>
 * ---
 * name: my-agent
 * description: Short description for the model to decide when to use this agent
 * tools: [bash, read, grep]
 * model: glm-5.1
 * ---
 *
 * You are a specialized agent that...
 * (markdown body = system prompt)
 * </pre>
 *
 * If no frontmatter is provided, the filename (without .md) is used as the name,
 * and the entire file content is the system prompt.
 */
public final class CustomAgentLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CustomAgentLoader.class);

    private CustomAgentLoader() {}

    /**
     * Load all agent definitions from the given directory.
     * Looks for {@code .kairo/agents/} relative to the working directory,
     * and also checks {@code ~/.kairo/agents/} for user-global agents.
     */
    public static List<SubagentDefinition> loadFromWorkspace(Path workingDir) {
        List<SubagentDefinition> results = new ArrayList<>();

        // Project-level agents
        Path projectAgents = workingDir.resolve(".kairo").resolve("agents");
        results.addAll(loadFromDir(projectAgents, null));

        // User-level agents
        Path userAgents = Path.of(System.getProperty("user.home"), ".kairo", "agents");
        if (!userAgents.equals(projectAgents)) {
            results.addAll(loadFromDir(userAgents, "user"));
        }

        return results;
    }

    /**
     * Load agent definitions from a single directory.
     */
    public static List<SubagentDefinition> loadFromDir(Path dir, String namespace) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SubagentDefinition> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         SubagentDefinition def = parseMarkdownAgent(p, namespace);
                         if (def != null) {
                             results.add(def);
                             LOG.debug("Loaded custom agent '{}' from {}", def.qualifiedName(), p);
                         }
                     } catch (Exception e) {
                         LOG.warn("Failed to parse agent file {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            LOG.warn("Failed to list agents directory {}: {}", dir, e.getMessage());
        }
        return results;
    }

    /**
     * Parse a single markdown agent file.
     */
    static SubagentDefinition parseMarkdownAgent(Path file, String namespace) throws IOException {
        String content = Files.readString(file);
        String fileName = file.getFileName().toString().replace(".md", "");

        // Check for YAML frontmatter
        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx > 0) {
                String frontmatter = content.substring(3, endIdx).trim();
                String body = content.substring(endIdx + 3).trim();

                String name = extractFrontmatterValue(frontmatter, "name", fileName);
                String description = extractFrontmatterValue(frontmatter, "description",
                        "Custom agent: " + name);
                String toolsStr = extractFrontmatterValue(frontmatter, "tools", "");
                String model = extractFrontmatterValue(frontmatter, "model", null);

                List<String> tools = toolsStr.isBlank() ? List.of()
                        : Arrays.stream(toolsStr.replace("[", "").replace("]", "").split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();

                return new SubagentDefinition(name, description, body, tools, model, namespace);
            }
        }

        // No frontmatter — filename is name, entire content is system prompt
        return new SubagentDefinition(
                fileName,
                "Custom agent: " + fileName,
                content,
                List.of(),
                null,
                namespace);
    }

    private static String extractFrontmatterValue(String frontmatter, String key, String defaultVal) {
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + ":")) {
                String value = line.substring(key.length() + 1).trim();
                if (value.isEmpty()) return defaultVal;
                // Remove quotes if present
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return defaultVal;
    }
}
