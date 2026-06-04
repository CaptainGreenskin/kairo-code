package io.kairo.code.service;

import io.kairo.api.hook.PostCompact;
import io.kairo.api.hook.PostCompactEvent;
import io.kairo.code.service.agent.AgentRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * Bridges framework {@link PostCompactEvent}s to SSE {@link AgentEvent#contextCompacted} events so
 * the web UI can display compaction notifications.
 *
 * <p>Runs after {@code PostCompactRecoveryHook} (order 100) to ensure recovery messages are already
 * appended before we report.
 */
public class CompactionEventBridgeHook {

    private static final Logger log = LoggerFactory.getLogger(CompactionEventBridgeHook.class);

    private final Sinks.Many<AgentEvent> sink;
    private final String sessionId;

    public CompactionEventBridgeHook(Sinks.Many<AgentEvent> sink, String sessionId) {
        this.sink = sink;
        this.sessionId = sessionId;
    }

    @PostCompact(order = 200)
    public void onPostCompact(PostCompactEvent event) {
        int tokensSaved = event.tokensSaved();
        String strategy = event.strategyUsed();
        log.info(
                "Compaction completed for session {}: saved {} tokens via {}",
                sessionId,
                tokensSaved,
                strategy);
        Sinks.EmitResult emit =
                AgentRuntimeContext.emitSerialized(
                        sink, AgentEvent.contextCompacted(sessionId, tokensSaved, 0));
        if (emit.isFailure()) {
            log.warn(
                    "Failed to emit CONTEXT_COMPACTED for session {}: {}", sessionId, emit);
        }
    }
}
