package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.workflow.WorkflowRunJournal;
import io.kairo.code.core.workflow.WorkflowRunJournal.RunSummary;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Lists workflow runs and supports resuming from a prior run.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :workflows} — list all workflow runs</li>
 *   <li>{@code :workflows <runId>} — show details of a specific run</li>
 * </ul>
 *
 * <p>To resume a workflow, use the {@code scripted_workflow} tool with
 * {@code resumeFromRunId} parameter in a prompt.
 */
public class WorkflowsCommand implements SlashCommand {

    private static final String SEP = "─".repeat(72);

    @Override
    public String name() {
        return "workflows";
    }

    @Override
    public String description() {
        return "List workflow runs (use resumeFromRunId in scripted_workflow to resume)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        String workingDir = context.config().workingDir();
        if (workingDir == null || workingDir.isBlank()) {
            writer.println("No working directory configured.");
            writer.flush();
            return;
        }

        Path root = Path.of(workingDir);

        if (args != null && !args.isBlank()) {
            showRunDetail(writer, root, args.trim());
        } else {
            listRuns(writer, root);
        }
        writer.flush();
    }

    private void listRuns(PrintWriter writer, Path root) {
        List<RunSummary> runs = WorkflowRunJournal.listRuns(root);
        if (runs.isEmpty()) {
            writer.println("No workflow runs found in " + root.resolve(WorkflowRunJournal.RUNS_DIR));
            return;
        }

        writer.println();
        writer.println("Workflow Runs");
        writer.println(SEP);
        writer.printf("%-14s  %-20s  %6s  %s%n", "Run ID", "Workflow", "Agents", "Last Saved");
        writer.println(SEP);

        for (RunSummary run : runs) {
            String savedAt = run.savedAt().length() > 19
                    ? run.savedAt().substring(0, 19).replace('T', ' ')
                    : run.savedAt();
            writer.printf("%-14s  %-20s  %6d  %s%n",
                    run.runId(),
                    truncate(run.workflowName(), 20),
                    run.entryCount(),
                    savedAt);
        }

        writer.println(SEP);
        writer.printf("%d run(s). To resume: ask the agent to use scripted_workflow "
                + "with resumeFromRunId.%n", runs.size());
    }

    private void showRunDetail(PrintWriter writer, Path root, String runId) {
        List<RunSummary> runs = WorkflowRunJournal.listRuns(root);
        var match = runs.stream().filter(r -> r.runId().equals(runId)).findFirst();
        if (match.isEmpty()) {
            writer.println("Run not found: " + runId);
            writer.println("Use :workflows to list available runs.");
            return;
        }
        RunSummary run = match.get();
        writer.println();
        writer.println("Workflow Run: " + run.runId());
        writer.println(SEP);
        writer.printf("  Workflow : %s%n", run.workflowName());
        writer.printf("  Agents   : %d cached results%n", run.entryCount());
        writer.printf("  Saved at : %s%n", run.savedAt());
        writer.println(SEP);
        writer.println("To resume, ask the agent:");
        writer.printf("  \"Resume workflow with resumeFromRunId '%s'\"%n", run.runId());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
