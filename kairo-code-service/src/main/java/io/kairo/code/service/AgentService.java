package io.kairo.code.service;

import io.kairo.api.agent.Agent;
import io.kairo.api.exception.AgentInterruptedException;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport-agnostic agent service bridge.
 *
 * <p>Manages session lifecycle and exposes a {@link Flux}-based event API
 * that any transport layer (STOMP / SSE / CLI) can subscribe to.
 */
@Component
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final Map<String, Sinks.Many<AgentEvent>> eventSinks = new ConcurrentHashMap<>();
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningState = new ConcurrentHashMap<>();

    /**
     * Create a new session. Returns the session ID.
     */
    public String createSession(CodeAgentConfig config) {
        String sessionId = UUID.randomUUID().toString();

        log.info("Creating session {} (model={}, workingDir={})",
                sessionId, config.modelName(), config.workingDir());

        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        eventSinks.put(sessionId, sink);

        AgentEventBridgeHook bridgeHook = new AgentEventBridgeHook(sink, sessionId);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        try {
            CodeAgentSession session = CodeAgentFactory.createSession(
                    config,
                    SessionOptions.empty()
                            .withApprovalHandler(approvalHandler)
                            .withHooks(List.of(bridgeHook)));

            SessionEntry entry = new SessionEntry(sessionId, config, session, approvalHandler);
            sessions.put(sessionId, entry);
            runningState.put(sessionId, new AtomicBoolean(false));

            log.info("Session {} created successfully", sessionId);
            return sessionId;
        } catch (Exception e) {
            log.error("Failed to create session {}", sessionId, e);
            sink.tryEmitComplete();
            eventSinks.remove(sessionId);
            throw e;
        }
    }

    /**
     * Send a user message to a session. Returns a Flux of events for this message.
     *
     * <p>Event sequence: AGENT_THINKING → TEXT_CHUNK* → TOOL_CALL* → TOOL_RESULT* → AGENT_DONE
     * or → AGENT_ERROR
     */
    public Flux<AgentEvent> sendMessage(String sessionId, String text) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session not found: " + sessionId, "SESSION_NOT_FOUND"));
        }

        AtomicBoolean running = runningState.get(sessionId);
        if (running == null || !running.compareAndSet(false, true)) {
            return Flux.just(AgentEvent.error(sessionId,
                    "Session is already running", "SESSION_BUSY"));
        }

        Sinks.Many<AgentEvent> sink = eventSinks.get(sessionId);
        if (sink == null) {
            runningState.put(sessionId, new AtomicBoolean(false));
            return Flux.just(AgentEvent.error(sessionId,
                    "Event sink not found for session: " + sessionId, "INTERNAL_ERROR"));
        }

        Agent agent = entry.session().agent();
        Msg userMsg = Msg.of(MsgRole.USER, text);

        return Flux.<AgentEvent>create(emitter -> {
                    // Emit thinking event
                    emitter.next(AgentEvent.thinking(sessionId));

                    AtomicBoolean completed = new AtomicBoolean(false);

                    agent.call(userMsg)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doFinally(signal -> {
                                runningState.put(sessionId, new AtomicBoolean(false));
                                if (!completed.get()) {
                                    if (signal != reactor.core.publisher.SignalType.ON_ERROR) {
                                        emitter.next(AgentEvent.done(sessionId, 0, 0.0));
                                    }
                                }
                                emitter.complete();
                            })
                            .subscribe(
                                    responseMsg -> {
                                        completed.set(true);
                                        // Token usage not directly available on Msg;
                                        // future enhancement: capture from hook events.
                                        emitter.next(AgentEvent.done(sessionId, 0, 0.0));
                                    },
                                    error -> {
                                        completed.set(true);
                                        String errorMsg = error.getMessage();
                                        String errorType = error.getClass().getSimpleName();
                                        if (error instanceof AgentInterruptedException) {
                                            errorType = "INTERRUPTED";
                                            errorMsg = "Agent execution was interrupted";
                                        }
                                        emitter.next(AgentEvent.error(sessionId, errorMsg, errorType));
                                        emitter.error(error);
                                    }
                            );
                })
                .mergeWith(sink.asFlux());
    }

    /**
     * Approve or reject a pending tool call.
     */
    public boolean approveTool(String sessionId, String toolCallId, boolean approved, String reason) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return false;
        }

        ApprovalResult result = approved
                ? ApprovalResult.allow()
                : ApprovalResult.denied(reason != null ? reason : "User denied");

        return entry.approvalHandler().resolveApproval(toolCallId, result);
    }

    /**
     * Interrupt the current agent execution.
     */
    public void stopAgent(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return;
        }

        try {
            entry.session().agent().interrupt();
            runningState.put(sessionId, new AtomicBoolean(false));
            log.info("Session {} stopped", sessionId);
        } catch (Exception e) {
            log.warn("Error stopping session {}", sessionId, e);
        }
    }

    /**
     * Destroy a session and clean up all resources.
     */
    public boolean destroySession(String sessionId) {
        SessionEntry entry = sessions.remove(sessionId);
        if (entry == null) {
            return false;
        }

        log.info("Destroying session {}", sessionId);
        entry.approvalHandler().cancelAll();
        runningState.remove(sessionId);

        Sinks.Many<AgentEvent> sink = eventSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        try {
            entry.session().agent().interrupt();
        } catch (Exception e) {
            log.debug("Error interrupting agent for session {}", sessionId, e);
        }
        return true;
    }

    /**
     * Return a list of active session summaries.
     */
    public List<SessionInfo> listSessions() {
        return sessions.values().stream()
                .map(e -> new SessionInfo(
                        e.sessionId(),
                        e.config().workingDir(),
                        e.config().modelName(),
                        e.createdAt(),
                        isRunning(e.sessionId())))
                .toList();
    }

    private boolean isRunning(String sessionId) {
        AtomicBoolean state = runningState.get(sessionId);
        return state != null && state.get();
    }

    /**
     * Holds a session and its associated state.
     */
    public record SessionEntry(
            String sessionId,
            CodeAgentConfig config,
            CodeAgentSession session,
            WebSocketApprovalHandler approvalHandler,
            long createdAt
    ) {
        public SessionEntry(String sessionId, CodeAgentConfig config,
                            CodeAgentSession session, WebSocketApprovalHandler approvalHandler) {
            this(sessionId, config, session, approvalHandler, System.currentTimeMillis());
        }

        public Agent agent() {
            return session.agent();
        }
    }
}
