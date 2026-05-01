package io.kairo.code.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for managing {@code .mcp.json} — the MCP (Model Context Protocol)
 * server configuration file consumed by the Claude Code SDK.
 *
 * <ul>
 *     <li>GET    /api/mcp/servers           — list all MCP servers</li>
 *     <li>POST   /api/mcp/servers           — add a new MCP server</li>
 *     <li>PUT    /api/mcp/servers/{name}    — update a server (full replace)</li>
 *     <li>DELETE /api/mcp/servers/{name}    — remove a server</li>
 *     <li>POST   /api/mcp/servers/{name}/toggle — toggle the {@code disabled} flag</li>
 * </ul>
 *
 * The configuration file path is fixed to {@code {workingDir}/.mcp.json}; the
 * client cannot specify another location.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    public record McpServerEntry(
            String name,
            String command,
            List<String> args,
            Map<String, String> env,
            boolean disabled) {}

    private final Path mcpFile;
    private final ObjectMapper objectMapper;

    public McpController(ServerProperties props, ObjectMapper objectMapper) {
        this.mcpFile = Paths.get(props.workingDir(), ".mcp.json");
        this.objectMapper = objectMapper;
    }

    @GetMapping("/servers")
    public List<McpServerEntry> listServers() throws IOException {
        ObjectNode root = readConfig();
        JsonNode serversNode = root.path("mcpServers");
        List<McpServerEntry> result = new ArrayList<>();
        if (!(serversNode instanceof ObjectNode servers)) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> it = servers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode node = e.getValue();
            List<String> args = new ArrayList<>();
            if (node.has("args") && node.get("args").isArray()) {
                node.get("args").forEach(a -> args.add(a.asText()));
            }
            Map<String, String> env = new LinkedHashMap<>();
            if (node.has("env") && node.get("env").isObject()) {
                node.get("env").fields()
                        .forEachRemaining(ev -> env.put(ev.getKey(), ev.getValue().asText()));
            }
            result.add(new McpServerEntry(
                    e.getKey(),
                    node.path("command").asText(""),
                    args,
                    env,
                    node.path("disabled").asBoolean(false)));
        }
        return result;
    }

    @PostMapping("/servers")
    public ResponseEntity<McpServerEntry> addServer(@RequestBody McpServerEntry entry) throws IOException {
        validateName(entry.name());
        ObjectNode root = readConfig();
        ObjectNode servers = getOrCreateServers(root);
        if (servers.has(entry.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Server '" + entry.name() + "' already exists");
        }
        servers.set(entry.name(), toNode(entry));
        writeConfig(root);
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @PutMapping("/servers/{name}")
    public McpServerEntry updateServer(@PathVariable String name,
                                       @RequestBody McpServerEntry entry) throws IOException {
        validateName(name);
        ObjectNode root = readConfig();
        ObjectNode servers = getOrCreateServers(root);
        if (!servers.has(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found");
        }
        // The path variable is authoritative for the server name; the body's
        // name is ignored to keep the operation a pure full-replace update.
        McpServerEntry normalized = new McpServerEntry(
                name, entry.command(), entry.args(), entry.env(), entry.disabled());
        servers.set(name, toNode(normalized));
        writeConfig(root);
        return normalized;
    }

    @DeleteMapping("/servers/{name}")
    public ResponseEntity<Void> deleteServer(@PathVariable String name) throws IOException {
        validateName(name);
        ObjectNode root = readConfig();
        ObjectNode servers = getOrCreateServers(root);
        if (!servers.has(name)) {
            return ResponseEntity.notFound().build();
        }
        servers.remove(name);
        writeConfig(root);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/servers/{name}/toggle")
    public McpServerEntry toggleServer(@PathVariable String name) throws IOException {
        validateName(name);
        ObjectNode root = readConfig();
        ObjectNode servers = getOrCreateServers(root);
        if (!servers.has(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found");
        }
        ObjectNode server = (ObjectNode) servers.get(name);
        boolean current = server.path("disabled").asBoolean(false);
        server.put("disabled", !current);
        writeConfig(root);
        return listServers().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private ObjectNode readConfig() throws IOException {
        if (!Files.exists(mcpFile)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode tree = objectMapper.readTree(mcpFile.toFile());
            if (tree instanceof ObjectNode obj) {
                return obj;
            }
            return objectMapper.createObjectNode();
        } catch (IOException e) {
            // Malformed file — start fresh rather than refusing all operations.
            return objectMapper.createObjectNode();
        }
    }

    private void writeConfig(ObjectNode root) throws IOException {
        Path parent = mcpFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(mcpFile,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    private ObjectNode getOrCreateServers(ObjectNode root) {
        JsonNode existing = root.get("mcpServers");
        if (!(existing instanceof ObjectNode obj)) {
            ObjectNode created = objectMapper.createObjectNode();
            root.set("mcpServers", created);
            return created;
        }
        return obj;
    }

    private ObjectNode toNode(McpServerEntry entry) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("command", entry.command() == null ? "" : entry.command());
        var argsArr = node.putArray("args");
        if (entry.args() != null) {
            entry.args().forEach(argsArr::add);
        }
        if (entry.env() != null && !entry.env().isEmpty()) {
            ObjectNode envNode = node.putObject("env");
            entry.env().forEach(envNode::put);
        }
        node.put("disabled", entry.disabled());
        return node;
    }

    private void validateName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_\\-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server name");
        }
    }
}
