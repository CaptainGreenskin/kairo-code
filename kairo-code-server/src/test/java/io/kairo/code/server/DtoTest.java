package io.kairo.code.server;

import io.kairo.code.server.dto.*;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the DTO classes.
 */
class DtoTest {

    @Test
    void createSessionRequest_defaults() {
        CreateSessionRequest request = new CreateSessionRequest(null, null, null, null);

        assertThat(request.workingDir()).endsWith("kairo-workspace");
        assertThat(request.model()).isEqualTo("gpt-4o");
    }

    @Test
    void createSessionRequest_customValues() {
        CreateSessionRequest request = new CreateSessionRequest(
                "/custom/dir", "anthropic", "claude-sonnet-4", "sk-test");

        assertThat(request.workingDir()).isEqualTo("/custom/dir");
        assertThat(request.model()).isEqualTo("claude-sonnet-4");
        assertThat(request.provider()).isEqualTo("anthropic");
        assertThat(request.apiKey()).isEqualTo("sk-test");
    }

    @Test
    void agentEvent_thinking() {
        AgentEvent event = AgentEvent.thinking("session-1");

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.AGENT_THINKING);
        assertThat(event.sessionId()).isEqualTo("session-1");
        assertThat(event.timestamp()).isGreaterThan(0);
    }

    @Test
    void agentEvent_textChunk() {
        AgentEvent event = AgentEvent.textChunk("session-1", "Hello world");

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.TEXT_CHUNK);
        assertThat(event.content()).isEqualTo("Hello world");
    }

    @Test
    void agentEvent_toolCall() {
        Map<String, Object> input = Map.of("command", "ls -la");
        AgentEvent event = AgentEvent.toolCall("session-1", "bash", input, "tc-123", true);

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.TOOL_CALL);
        assertThat(event.toolName()).isEqualTo("bash");
        assertThat(event.toolInput()).isEqualTo(input);
        assertThat(event.toolCallId()).isEqualTo("tc-123");
        assertThat(event.requiresApproval()).isTrue();
    }

    @Test
    void agentEvent_toolResult() {
        AgentEvent event = AgentEvent.toolResult("session-1", "tc-123", "output");

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.TOOL_RESULT);
        assertThat(event.toolCallId()).isEqualTo("tc-123");
        assertThat(event.toolResult()).isEqualTo("output");
    }

    @Test
    void agentEvent_done() {
        AgentEvent event = AgentEvent.done("session-1", 1500, 0.03);

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.AGENT_DONE);
        assertThat(event.tokenUsage()).isEqualTo(1500);
        assertThat(event.cost()).isEqualTo(0.03);
    }

    @Test
    void agentEvent_error() {
        AgentEvent event = AgentEvent.error("session-1", "API error", "API_EXCEPTION");

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(event.errorMessage()).isEqualTo("API error");
        assertThat(event.errorType()).isEqualTo("API_EXCEPTION");
    }

    @Test
    void sessionInfo_fields() {
        SessionInfo info = new SessionInfo("s1", "/tmp", "gpt-4o", 12345L, true);

        assertThat(info.sessionId()).isEqualTo("s1");
        assertThat(info.workingDir()).isEqualTo("/tmp");
        assertThat(info.model()).isEqualTo("gpt-4o");
        assertThat(info.createdAt()).isEqualTo(12345L);
        assertThat(info.running()).isTrue();
    }

    @Test
    void serverConfigResponse_fields() {
        ServerConfigResponse response = new ServerConfigResponse("openai", "gpt-4o", "/workspace");

        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.workingDir()).isEqualTo("/workspace");
    }
}
