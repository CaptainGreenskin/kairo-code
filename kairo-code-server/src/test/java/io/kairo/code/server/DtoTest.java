package io.kairo.code.server;

import io.kairo.code.server.dto.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the server-layer DTO classes.
 */
class DtoTest {

    @Test
    void createSessionRequest_nullsPassthrough() {
        CreateSessionRequest request = new CreateSessionRequest(null, null, null, null);

        assertThat(request.workingDir()).isNull();
        assertThat(request.model()).isNull();
        assertThat(request.provider()).isNull();
        assertThat(request.apiKey()).isNull();
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
    void createSessionResponse_fields() {
        CreateSessionResponse response = new CreateSessionResponse("s1", "/workspace", "gpt-4o");

        assertThat(response.sessionId()).isEqualTo("s1");
        assertThat(response.workingDir()).isEqualTo("/workspace");
        assertThat(response.model()).isEqualTo("gpt-4o");
    }

    @Test
    void agentMessageRequest_fields() {
        AgentMessageRequest request = new AgentMessageRequest("session-1", "hello");

        assertThat(request.sessionId()).isEqualTo("session-1");
        assertThat(request.message()).isEqualTo("hello");
    }

    @Test
    void toolApprovalRequest_fields() {
        ToolApprovalRequest request = new ToolApprovalRequest("session-1", "tc-1", true, null);

        assertThat(request.sessionId()).isEqualTo("session-1");
        assertThat(request.toolCallId()).isEqualTo("tc-1");
        assertThat(request.approved()).isTrue();
        assertThat(request.reason()).isNull();
    }

    @Test
    void serverConfigResponse_fields() {
        ServerConfigResponse response = new ServerConfigResponse("openai", "gpt-4o", "/workspace", "https://api.openai.com", true);

        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.workingDir()).isEqualTo("/workspace");
        assertThat(response.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(response.apiKeySet()).isTrue();
    }
}
