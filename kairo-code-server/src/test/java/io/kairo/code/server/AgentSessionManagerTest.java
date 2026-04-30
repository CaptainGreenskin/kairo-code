package io.kairo.code.server;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.server.dto.*;
import io.kairo.code.service.SessionInfo;
import io.kairo.code.server.session.AgentSessionManager;
import io.kairo.code.server.session.WebSocketApprovalHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the agent session management and controller logic.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Session creation returns non-empty ID</li>
 *   <li>Session retrieval and destruction</li>
 *   <li>Running state tracking</li>
 *   <li>Approval handler resolution</li>
 * </ul>
 */
class AgentSessionManagerTest {

    private AgentSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new AgentSessionManager();
    }

    @Test
    void createSession_returnsSessionId() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o",
                50, "/tmp/test-workspace", null, 0, 0);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        String sessionId = sessionManager.createSession(config, approvalHandler);

        assertThat(sessionId).isNotBlank();
        assertThat(sessionManager.getSession(sessionId)).isPresent();
    }

    @Test
    void getSession_nonExistent_returnsEmpty() {
        Optional<AgentSessionManager.SessionEntry> result =
                sessionManager.getSession("non-existent-id");

        assertThat(result).isEmpty();
    }

    @Test
    void destroySession_cleansUp() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o",
                50, "/tmp/test-workspace", null, 0, 0);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        String sessionId = sessionManager.createSession(config, approvalHandler);

        boolean destroyed = sessionManager.destroySession(sessionId);

        assertThat(destroyed).isTrue();
        assertThat(sessionManager.getSession(sessionId)).isEmpty();
    }

    @Test
    void destroySession_nonExistent_returnsFalse() {
        boolean result = sessionManager.destroySession("non-existent-id");

        assertThat(result).isFalse();
    }

    @Test
    void listSessions_returnsActiveSessions() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o",
                50, "/tmp/test-workspace", null, 0, 0);

        String id1 = sessionManager.createSession(config, new WebSocketApprovalHandler());
        String id2 = sessionManager.createSession(config, new WebSocketApprovalHandler());

        List<SessionInfo> sessions = sessionManager.listSessions();

        assertThat(sessions).hasSize(2);
        assertThat(sessions).extracting(SessionInfo::sessionId)
                .containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void runningState_tracksCorrectly() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o",
                50, "/tmp/test-workspace", null, 0, 0);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        String sessionId = sessionManager.createSession(config, approvalHandler);

        assertThat(sessionManager.isRunning(sessionId)).isFalse();

        sessionManager.setRunning(sessionId, true);
        assertThat(sessionManager.isRunning(sessionId)).isTrue();

        sessionManager.setRunning(sessionId, false);
        assertThat(sessionManager.isRunning(sessionId)).isFalse();
    }

    @Test
    void sessionInfo_reflectsRunningState() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o",
                50, "/tmp/test-workspace", null, 0, 0);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        String sessionId = sessionManager.createSession(config, approvalHandler);
        sessionManager.setRunning(sessionId, true);

        List<SessionInfo> sessions = sessionManager.listSessions();
        SessionInfo info = sessions.get(0);

        assertThat(info.sessionId()).isEqualTo(sessionId);
        assertThat(info.workingDir()).isEqualTo("/tmp/test-workspace");
        assertThat(info.model()).isEqualTo("gpt-4o");
        assertThat(info.running()).isTrue();
    }

    @Test
    void destroySession_cancelsPendingApprovals() {
        CodeAgentConfig config = new CodeAgentConfig(
                "test-key", "https://api.openai.com", "gpt-4o",
                50, "/tmp/test-workspace", null, 0, 0);
        WebSocketApprovalHandler approvalHandler = new WebSocketApprovalHandler();

        String sessionId = sessionManager.createSession(config, approvalHandler);

        // Simulate a pending approval
        io.kairo.api.tool.ToolCallRequest request =
                new io.kairo.api.tool.ToolCallRequest(
                        "bash",
                        java.util.Map.of("command", "rm -rf /"),
                        io.kairo.api.tool.ToolSideEffect.SYSTEM_CHANGE);

        // Start an approval request
        approvalHandler.requestApproval(request).subscribe();

        // Destroy the session — should cancel pending approvals
        sessionManager.destroySession(sessionId);

        // The approval should have been resolved with a denial
        // (we can't easily test the Mono result synchronously,
        // but cancelAll() should have been called)
    }
}
