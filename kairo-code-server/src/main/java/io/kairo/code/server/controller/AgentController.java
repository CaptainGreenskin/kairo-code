package io.kairo.code.server.controller;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.AgentService;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * STOMP message controller — thin adapter over {@link AgentService}.
 *
 * <p>Endpoints (all under /app prefix):
 * <ul>
 *   <li>/app/agent/create — create a new session</li>
 *   <li>/app/agent/message — send a user message to the agent</li>
 *   <li>/app/agent/approve — approve or reject a tool call</li>
 *   <li>/app/agent/stop — stop the current agent execution</li>
 *   <li>/app/agent/bind-session — bind to an existing session and restore history</li>
 * </ul>
 *
 * Events are pushed to /topic/session/{sessionId}.
 */
@Controller
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final AgentService agentService;
    private final ServerProperties serverProperties;

    public AgentController(SimpMessagingTemplate messagingTemplate,
                           AgentService agentService,
                           ServerProperties serverProperties) {
        this.messagingTemplate = messagingTemplate;
        this.agentService = agentService;
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
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model()
                : serverProperties.model();
        String workingDir = (request.workingDir() != null && !request.workingDir().isBlank())
                ? request.workingDir()
                : serverProperties.workingDir();

        CodeAgentConfig config = new CodeAgentConfig(
                apiKey,
                baseUrl,
                model,
                50,
                workingDir,
                null,
                0,
                0
        );

        String sessionId = agentService.createSession(config);

        CreateSessionResponse response = new CreateSessionResponse(
                sessionId, config.workingDir(), config.modelName());
        messagingTemplate.convertAndSend("/topic/session/" + sessionId, response);
        messagingTemplate.convertAndSend("/topic/session/created", response);

        log.info("Session {} created via WebSocket", sessionId);
    }

    /**
     * Send a user message to an agent session. Events are streamed
     * to the session topic via Flux subscription.
     */
    @MessageMapping("/agent/message")
    public void sendMessage(@Payload AgentMessageRequest request) {
        agentService.sendMessage(request.sessionId(), request.message(),
                        request.imageData(), request.imageMediaType())
                .subscribe(
                        event -> messagingTemplate.convertAndSend(
                                "/topic/session/" + request.sessionId(), event),
                        error -> log.error("Error in session {} message stream", request.sessionId(), error)
                );

        log.info("Message sent to session {}", request.sessionId());
    }

    /**
     * Approve or reject a pending tool call.
     */
    @MessageMapping("/agent/approve")
    public void approveTool(@Payload ToolApprovalRequest request) {
        boolean resolved = agentService.approveTool(
                request.sessionId(),
                request.toolCallId(),
                request.approved(),
                request.reason());

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
        agentService.stopAgent(request.sessionId());
        log.info("Stop requested for session {}", request.sessionId());
    }

    /**
     * Bind to an existing session and restore its history.
     * The session must already exist (created via /app/agent/create).
     * Pushes a SESSION_RESTORED event with the message history.
     */
    @MessageMapping("/agent/bind-session")
    public void bindSession(@Payload BindSessionRequest request) {
        AgentEvent event = agentService.bindSession(request.sessionId());
        if (event != null) {
            messagingTemplate.convertAndSend("/topic/session/" + request.sessionId(), event);
            log.info("Session {} restored via WebSocket", request.sessionId());
        } else {
            pushError(request.sessionId(), "Session not found: " + request.sessionId(), "SESSION_NOT_FOUND");
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
        return serverProperties.apiKey();
    }
}
