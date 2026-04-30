package io.kairo.code.server.session;

import io.kairo.api.agent.Agent;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.server.dto.SessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages agent sessions. Creates, stores, and destroys
 * {@link CodeAgentSession} instances keyed by a UUID session ID.
 */
@Component
public class AgentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionManager.class);

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningState = new ConcurrentHashMap<>();

    /**
     * Create a new session with the given configuration and approval handler.
     */
    public String createSession(CodeAgentConfig config, WebSocketApprovalHandler approvalHandler) {
        String sessionId = UUID.randomUUID().toString();

        log.info("Creating session {} (model={}, workingDir={})",
                sessionId, config.modelName(), config.workingDir());

        try {
            CodeAgentSession session = CodeAgentFactory.createSession(
                    config,
                    SessionOptions.empty()
                            .withApprovalHandler(approvalHandler));

            SessionEntry entry = new SessionEntry(
                    sessionId, config, session, approvalHandler);
            sessions.put(sessionId, entry);
            runningState.put(sessionId, new AtomicBoolean(false));

            log.info("Session {} created successfully", sessionId);
            return sessionId;
        } catch (Exception e) {
            log.error("Failed to create session {}", sessionId, e);
            throw e;
        }
    }

    /**
     * Get an active session by ID.
     */
    public Optional<SessionEntry> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Set the running state for a session.
     */
    public void setRunning(String sessionId, boolean running) {
        AtomicBoolean state = runningState.get(sessionId);
        if (state != null) {
            state.set(running);
        }
    }

    /**
     * Check if a session is currently running.
     */
    public boolean isRunning(String sessionId) {
        AtomicBoolean state = runningState.get(sessionId);
        return state != null && state.get();
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
