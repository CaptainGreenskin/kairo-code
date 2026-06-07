package io.kairo.code.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.api.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

@Tool(
        name = "scripted_workflow",
        description = "Execute a JavaScript workflow script that orchestrates multiple child agents "
                + "with parallel execution, pipelines, and structured output. Use for complex "
                + "multi-step tasks that benefit from concurrent agent work — e.g. find-and-verify, "
                + "map-reduce over files, multi-reviewer consensus. Scripts use primitives: "
                + "agent(prompt, opts), parallel(descriptions), pipeline(items, ...stages), "
                + "phase(title), log(message). Scripts must begin with "
                + "'export const meta = { name, description }' followed by the orchestration body.",
        category = ToolCategory.AGENT_AND_TASK,
        sideEffect = ToolSideEffect.WRITE,
        timeoutSeconds = 14400)
public class ScriptedWorkflowTool implements SyncTool {

    private static final Logger log = LoggerFactory.getLogger(ScriptedWorkflowTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKFLOWS_DIR = ".kairo/workflows";

    @ToolParam(description = "Inline JavaScript workflow script. Must begin with "
            + "'export const meta = { name, description, phases }' block.", required = false)
    private String script;

    @ToolParam(description = "Load workflow from .kairo/workflows/<name>.js", required = false)
    private String name;

    @ToolParam(description = "Absolute path to a .js workflow script file.", required = false)
    private String scriptPath;

    @ToolParam(description = "Arguments passed to the script as the global 'args' variable. "
            + "Pass arrays/objects as JSON.", required = false)
    private Object args;

    @ToolParam(description = "Resume from a prior workflow run. Pass the runId returned by "
            + "a previous invocation. Unchanged agent calls return cached results instantly.",
            required = false)
    private String resumeFromRunId;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        WorkflowDependencies deps = ctx.getBean(WorkflowDependencies.class).orElse(null);
        if (deps == null) {
            return Mono.just(ToolResult.error(null,
                    "ScriptedWorkflowTool requires WorkflowDependencies — not available in this "
                            + "session (child sessions cannot run workflows)."));
        }

        String scriptSource;
        try {
            scriptSource = resolveScript(input, ctx.workspace());
        } catch (IOException e) {
            return Mono.just(ToolResult.error(null,
                    "Failed to read workflow script: " + e.getMessage()));
        }
        if (scriptSource == null) {
            return Mono.just(ToolResult.error(null,
                    "One of 'script', 'name', or 'scriptPath' is required."));
        }

        Workspace ws = ctx.workspace();
        return Mono.fromCallable(() -> runWorkflow(scriptSource, input, deps, ws));
    }

    private ToolResult runWorkflow(String scriptSource, Map<String, Object> input,
                                   WorkflowDependencies deps, Workspace workspace) {
        long startMs = System.currentTimeMillis();

        WorkflowMeta meta;
        try {
            meta = WorkflowMetaParser.parse(scriptSource);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(null, "Failed to parse workflow meta: " + e.getMessage());
        }

        String scriptBody = WorkflowMetaParser.extractScriptBody(scriptSource);
        Object scriptArgs = input.get("args");

        // Resume support: load existing journal or create new one
        WorkflowRunJournal journal = null;
        Object rawResumeId = input.get("resumeFromRunId");
        Path workspaceRoot = workspace.root();
        if (rawResumeId instanceof String resumeId && !resumeId.isBlank()) {
            try {
                journal = WorkflowRunJournal.resume(resumeId, workspaceRoot);
                log.info("Resuming workflow '{}' from run {}", meta.name(), resumeId);
            } catch (IOException e) {
                return ToolResult.error(null, "Failed to resume: " + e.getMessage());
            }
        } else {
            journal = WorkflowRunJournal.create(workspaceRoot);
        }

        journal.setWorkflowName(meta.name());
        WorkflowRuntime runtime = new WorkflowRuntime(deps, journal);
        try (WorkflowScriptEngine engine = new WorkflowScriptEngine(runtime, scriptArgs)) {
            log.info("Executing workflow '{}' (runId={})", meta.name(), runtime.runId());

            Object result = engine.execute(scriptBody);

            long durationMs = System.currentTimeMillis() - startMs;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runId", runtime.runId());
            metadata.put("workflow", meta.name());
            metadata.put("durationMs", durationMs);

            // Persist journal for future resume
            try { journal.save(); } catch (IOException e) {
                log.warn("Failed to save workflow journal: {}", e.getMessage());
            }

            String output = formatResult(meta, result, runtime.runId(), durationMs);
            return ToolResult.success(null, output, metadata);
        } catch (WorkflowExecutionException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.warn("Workflow '{}' failed after {}ms: {}", meta.name(), durationMs, e.getMessage());
            // Save journal even on failure so partial results can be resumed
            try { journal.save(); } catch (IOException ex) { /* best effort */ }
            return ToolResult.error(null, "Workflow '" + meta.name() + "' failed: " + e.getMessage());
        } finally {
            runtime.shutdown();
        }
    }

    private String resolveScript(Map<String, Object> input, Workspace workspace) throws IOException {
        Object rawScript = input.get("script");
        if (rawScript instanceof String s && !s.isBlank()) {
            return s;
        }

        Object rawName = input.get("name");
        if (rawName instanceof String n && !n.isBlank()) {
            Path file = resolveWorkflowFile(n.trim(), workspace);
            if (file == null) return null;
            return Files.readString(file);
        }

        Object rawPath = input.get("scriptPath");
        if (rawPath instanceof String p && !p.isBlank()) {
            Path file = Path.of(p.trim());
            if (Files.isRegularFile(file)) {
                return Files.readString(file);
            }
        }

        return null;
    }

    private Path resolveWorkflowFile(String name, Workspace workspace) {
        Path dir = workspace.root().resolve(WORKFLOWS_DIR);
        Path jsFile = dir.resolve(name + ".js");
        if (Files.isRegularFile(jsFile)) return jsFile;
        Path direct = dir.resolve(name);
        if (Files.isRegularFile(direct)) return direct;
        return null;
    }

    private static String formatResult(WorkflowMeta meta, Object result,
                                       String runId, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow '").append(meta.name()).append("' completed successfully.\n");
        sb.append("RunId: ").append(runId).append("\n");
        sb.append("Duration: ").append(durationMs).append("ms\n");
        if (result != null) {
            sb.append("\nResult:\n");
            if (result instanceof Map || result instanceof Object[]) {
                try {
                    sb.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                } catch (Exception e) {
                    sb.append(result);
                }
            } else {
                sb.append(result);
            }
        }
        return sb.toString();
    }
}
