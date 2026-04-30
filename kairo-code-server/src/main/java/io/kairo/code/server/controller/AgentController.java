package io.kairo.code.server.controller;

import io.kairo.api.agent.Agent;
import io.kairo.api.exception.AgentInterruptedException;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.dto.*;
import io.kairo.code.server.session.AgentSessionManager;
import io.kairo.code.server.session.AgentSessionManager.SessionEntry;
import io.kairo.code.server.session.WebSocketApprovalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * STOMP message controller that handles agent session operations
 * over WebSocket.
 *
 * <p>Endpoints (all under /app prefix):
 * <ul>
 *   <li>/app/agent/create — create a new session</li>
 *   <li>/app/agent/message — send a user message to the agent</li>
 *   <li>/app/agent/approve — approve or reject a tool call</li>
 *   <li>/app/agent/stop — stop the current agent execution</li>
 * </ul>
 *
 * Events are pushed to /topic/session/{sessionId}.
 */
@Controller
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final AgentSessionManager sessionManager;
    private final ServerProperties serverProperties;

    public AgentController(SimpMessagingTemplate messagingTemplate,
                           AgentSessionManager sessionManager,
                           @Lazy ServerProperties serverProperties) {
        this.messagingTemplate = messagingTemplate;
        this.sessionManager = sessionManager;
        this.serverProperties = serverProperties;
    }

    /**
     * Create a new agent session.
     */
    @MessageMapping("/agent/create")
    public void createSession(@Payload CreateSessionRequest request) {
        String apiKey = request.apiKey() != null && !request.apiKey().isBlank()
                ? request.apiKey()
                : resolveApiKey();
        String baseUrl = resolveBaseUrl(request.provider(), serverProperties);

        CodeAgentConfig config = new CodeAgentConfig(
                apiKey,
                baseUrl,
                request.model(),
                50,
                request.workingDir(),
                null,
                0,
                0
        );

        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();
        String sessionId = sessionManager.createSession(config, approvalHandler);

        CreateSessionResponse response = new CreateSessionResponse(
                sessionId, config.workingDir(), config.modelName());
        messagingTemplate.convertAndSend("/topic/session/" + sessionId, response);

        log.info("Session {} created via WebSocket", sessionId);
    }

    /**
     * Send a user message to an agent session. The agent runs asynchronously
     * and events are pushed to the session topic.
     */
    @MessageMapping("/agent/message")
    public void sendMessage(@Payload AgentMessageRequest request) {
        SessionEntry entry = sessionManager.getSession(request.sessionId())
                .orElse(null);
        if (entry == null) {
            pushError(request.sessionId(), "Session not found: " + request.sessionId(), "SESSION_NOT_FOUND");
            return;
        }

        if (sessionManager.isRunning(request.sessionId())) {
            pushError(request.sessionId(), "Session is already running", "SESSION_BUSY");
            return;
        }

        // Mark session as running
        sessionManager.setRunning(request.sessionId(), true);

        Agent agent = entry.session().agent();
        Msg userMsg = Msg.of(MsgRole.USER, request.message());

        // Push thinking event
        messagingTemplate.convertAndSend("/topic/session/" + request.sessionId(),
                AgentEvent.thinking(request.sessionId()));

        // Subscribe to the agent call and stream events
        AtomicBoolean completed = new AtomicBoolean(false);

        agent.call(userMsg)
                .doFinally(signal -> {
                    sessionManager.setRunning(request.sessionId(), false);
                    if (!completed.get()) {
                        // Agent was cancelled or errored without completing
                        if (signal == reactor.core.publisher.SignalType.ON_ERROR) {
                            // Error handled in onError
                        } else {
                            messagingTemplate.convertAndSend("/topic/session/" + request.sessionId(),
                                    AgentEvent.done(request.sessionId(), 0, 0.0));
                        }
                    }
                })
                .subscribe(
                        responseMsg -> {
                            completed.set(true);
                            // Extract token usage from the response
                            long tokens = 0;
                            double cost = 0.0;
                            // Token usage is not directly available on Msg;
                            // we report 0 for now. A future enhancement would
                            // capture usage from the hook events.
                            messagingTemplate.convertAndSend("/topic/session/" + request.sessionId(),
                                    AgentEvent.done(request.sessionId(), tokens, cost));
                        },
                        error -> {
                            completed.set(true);
                            String errorMsg = error.getMessage();
                            String errorType = error.getClass().getSimpleName();
                            if (error instanceof AgentInterruptedException) {
                                errorType = "INTERRUPTED";
                                errorMsg = "Agent execution was interrupted";
                            }
                            pushError(request.sessionId(), errorMsg, errorType);
                        }
                );

        log.info("Message sent to session {}", request.sessionId());
    }

    /**
     * Approve or reject a pending tool call.
     */
    @MessageMapping("/agent/approve")
    public void approveTool(@Payload ToolApprovalRequest request) {
        SessionEntry entry = sessionManager.getSession(request.sessionId())
                .orElse(null);
        if (entry == null) {
            pushError(request.sessionId(), "Session not found", "SESSION_NOT_FOUND");
            return;
        }

        boolean resolved = entry.approvalHandler().resolveApproval(
                request.toolCallId(),
                request.approved()
                        ? io.kairo.api.tool.ApprovalResult.allow()
                        : io.kairo.api.tool.ApprovalResult.denied(
                                request.reason() != null ? request.reason() : "User denied"));

        if (!resolved) {
            pushError(request.sessionId(),
                    "No pending approval for toolCallId: " + request.toolCallId(),
                    "NO_PENDING_APPROVAL");
        } else {
            log.info("Tool call {} {} in session {}",
                    request.toolCallId(),
                    request.approved() ? "approved" : "denied",
                    request.sessionId());
        }
    }

    /**
     * Stop the current agent execution.
     */
    @MessageMapping("/agent/stop")
    public void stopAgent(@Payload AgentMessageRequest request) {
        SessionEntry entry = sessionManager.getSession(request.sessionId())
                .orElse(null);
        if (entry == null) {
            pushError(request.sessionId(), "Session not found", "SESSION_NOT_FOUND");
            return;
        }

        try {
            entry.session().agent().interrupt();
            sessionManager.setRunning(request.sessionId(), false);
            messagingTemplate.convertAndSend("/topic/session/" + request.sessionId(),
                    AgentEvent.done(request.sessionId(), 0, 0.0));
            log.info("Session {} stopped", request.sessionId());
        } catch (Exception e) {
            log.warn("Error stopping session {}", request.sessionId(), e);
        }
    }

    // -- Private helpers --

    private void pushError(String sessionId, String message, String type) {
        messagingTemplate.convertAndSend("/topic/session/" + sessionId,
                AgentEvent.error(sessionId, message, type));
    }

    private String resolveBaseUrl(String provider, ServerProperties props) {
        if (provider == null || provider.isBlank()) {
            return props.baseUrl();
        }
        return switch (provider.toLowerCase()) {
            case "openai" -> "https://api.openai.com";
            case "anthropic" -> "https://api.anthropic.com";
            default -> props.baseUrl();
        };
    }

    private String resolveApiKey() {
        // API key should come from server config.
        // In production, this is set via KAIRO_CODE_API_KEY env var.
        return serverProperties.apiKey();
    }
}
