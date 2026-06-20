package io.kairo.code.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Msg;
import io.kairo.core.agent.continuation.AgentContinuationStrategy;
import io.kairo.core.agent.continuation.ContinuationContext;
import io.kairo.core.agent.continuation.ContinuationDecision;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Nudges the agent to continue when the todo list ({@code .kairo/todos.json}) has
 * pending items that haven't been completed yet.
 *
 * <p>Reads the on-disk todo file directly — no extensionData bridging needed.
 * Has a per-session nudge budget to prevent infinite loops (the root cause that
 * forced the generic {@code withSmartContinuation()} to be disabled).
 */
public final class IncompleteTodoContinuationStrategy implements AgentContinuationStrategy {

    private static final Logger log = LoggerFactory.getLogger(IncompleteTodoContinuationStrategy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_NUDGES = 30;

    private final Path workingDir;
    private final int maxNudges;

    public IncompleteTodoContinuationStrategy(Path workingDir) {
        this(workingDir, DEFAULT_MAX_NUDGES);
    }

    public IncompleteTodoContinuationStrategy(Path workingDir, int maxNudges) {
        this.workingDir = workingDir;
        this.maxNudges = maxNudges;
    }

    @Override
    public Mono<ContinuationDecision> decide(ContinuationContext ctx) {
        if (!ctx.withinNudgeBudget(maxNudges)) {
            return Mono.just(ContinuationDecision.Pass.INSTANCE);
        }

        int pendingCount = countPendingTodos();
        if (pendingCount <= 0) {
            return Mono.just(ContinuationDecision.Pass.INSTANCE);
        }

        log.info("Agent '{}' has {} pending todo(s) — nudging to continue",
                ctx.agentName(), pendingCount);

        Msg synthetic = Msg.nudge(
                "You have " + pendingCount + " pending TODO items that are not completed. "
                        + "Continue working on them now. Call a tool to make progress — "
                        + "do not respond with text only.",
                name());
        return Mono.just(new ContinuationDecision.Nudge(synthetic,
                "pending_todos=" + pendingCount));
    }

    @Override
    public String name() {
        return "IncompleteTodo";
    }

    private int countPendingTodos() {
        if (workingDir == null) return 0;
        Path todosFile = workingDir.resolve(".kairo/todos.json");
        if (!Files.exists(todosFile)) return 0;

        try {
            String content = Files.readString(todosFile).trim();
            if (content.isEmpty() || "[]".equals(content)) return 0;

            JsonNode array = MAPPER.readTree(content);
            if (!array.isArray()) return 0;

            int pending = 0;
            for (JsonNode item : array) {
                String status = item.has("status") ? item.get("status").asText() : "";
                if (!"completed".equals(status)) {
                    pending++;
                }
            }
            return pending;
        } catch (Exception e) {
            log.debug("Failed to read todos.json: {}", e.getMessage());
            return 0;
        }
    }
}
