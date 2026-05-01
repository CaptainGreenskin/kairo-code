package io.kairo.code.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/hooks")
public class HookConfigController {

    private static final List<HookMeta> KNOWN_HOOKS = List.of(
        new HookMeta("AutoCommitOnSuccessHook", "Auto-commit when all tests pass"),
        new HookMeta("CheckpointWriterHook", "Write checkpoint after each successful turn"),
        new HookMeta("CompileErrorFeedbackHook", "Inject compile error context on failure"),
        new HookMeta("ContextCompactionHook", "Summarize context when window is nearly full"),
        new HookMeta("ContextWindowGuardHook", "Warn agent when context window is large"),
        new HookMeta("ExecutionTraceHook", "Record execution trace to JSONL"),
        new HookMeta("FullTestSuiteHook", "Remind agent to run full test suite before finishing"),
        new HookMeta("MaxTurnsGuardHook", "Inject wrap-up hint when max turns reached"),
        new HookMeta("MissingTestHintHook", "Suggest creating tests when none exist"),
        new HookMeta("NoWriteDetectedHook", "Warn when agent only talks without writing files"),
        new HookMeta("PlanModeHook", "Show plan and wait for approval before executing"),
        new HookMeta("PlanWithoutActionHook", "Block responses that plan but take no action"),
        new HookMeta("PostBatchEditVerifyHook", "Run tests after batch of file edits"),
        new HookMeta("PostEditHintHook", "Hint to verify after single file edit"),
        new HookMeta("RepetitiveToolHook", "Detect and break repetitive tool call loops"),
        new HookMeta("SessionResultWriterHook", "Write structured result file on session end"),
        new HookMeta("StaleReadDetectorHook", "Warn when agent reads same file repeatedly"),
        new HookMeta("TestFailureFeedbackHook", "Inject structured test failure context"),
        new HookMeta("TextOnlyStallHook", "Detect text-only stalls with no tool usage"),
        new HookMeta("ToolBudgetHook", "Enforce per-tool call budget limits"),
        new HookMeta("UnfulfilledInstructionHook", "Detect when instructions remain unexecuted")
    );

    public record HookMeta(String name, String description) {}
    public record HookInfo(String name, String description, boolean enabled) {}

    private final ServerProperties props;
    private final ObjectMapper objectMapper;

    public HookConfigController(ServerProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<HookInfo> listHooks() throws IOException {
        Set<String> disabled = loadDisabled();
        return KNOWN_HOOKS.stream()
                .map(h -> new HookInfo(h.name(), h.description(), !disabled.contains(h.name())))
                .toList();
    }

    @PostMapping("/{name}/toggle")
    public ResponseEntity<HookInfo> toggle(@PathVariable String name) throws IOException {
        HookMeta meta = KNOWN_HOOKS.stream().filter(h -> h.name().equals(name)).findFirst().orElse(null);
        if (meta == null) return ResponseEntity.notFound().build();

        Set<String> disabled = new HashSet<>(loadDisabled());
        boolean nowEnabled;
        if (disabled.contains(name)) {
            disabled.remove(name);
            nowEnabled = true;
        } else {
            disabled.add(name);
            nowEnabled = false;
        }
        saveDisabled(disabled);
        return ResponseEntity.ok(new HookInfo(name, meta.description(), nowEnabled));
    }

    private Path hooksFile() {
        return Paths.get(props.workingDir(), ".kairo-code", "hooks.json");
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadDisabled() throws IOException {
        Path file = hooksFile();
        if (!Files.exists(file)) return new HashSet<>();
        var node = objectMapper.readTree(file.toFile());
        var disabled = node.path("disabled");
        if (!disabled.isArray()) return new HashSet<>();
        Set<String> result = new HashSet<>();
        disabled.forEach(n -> result.add(n.asText()));
        return result;
    }

    private void saveDisabled(Set<String> disabled) throws IOException {
        Path file = hooksFile();
        Files.createDirectories(file.getParent());
        ObjectNode root = objectMapper.createObjectNode();
        var arr = root.putArray("disabled");
        disabled.forEach(arr::add);
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }
}
