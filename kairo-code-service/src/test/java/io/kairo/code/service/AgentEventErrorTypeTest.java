package io.kairo.code.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentEvent} errorType field serialization.
 */
class AgentEventErrorTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void error_event_serializes_errorType() throws Exception {
        AgentEvent event = AgentEvent.error("sess-1", "rate limited", "RATE_LIMITED");

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"errorType\":\"RATE_LIMITED\"");
        assertThat(json).contains("\"errorMessage\":\"rate limited\"");
        assertThat(json).contains("\"type\":\"AGENT_ERROR\"");
        assertThat(json).contains("\"sessionId\":\"sess-1\"");
    }

    @Test
    void error_event_serializes_auth_failure() throws Exception {
        AgentEvent event = AgentEvent.error("sess-2", "bad key", "AUTH_FAILURE");

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"errorType\":\"AUTH_FAILURE\"");
    }

    @Test
    void error_event_serializes_quota_exceeded() throws Exception {
        AgentEvent event = AgentEvent.error("sess-3", "over budget", "QUOTA_EXCEEDED");

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"errorType\":\"QUOTA_EXCEEDED\"");
    }

    @Test
    void error_event_serializes_provider_error() throws Exception {
        AgentEvent event = AgentEvent.error("sess-4", "server error", "PROVIDER_ERROR");

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"errorType\":\"PROVIDER_ERROR\"");
    }

    @Test
    void error_event_deserializes_errorType() throws Exception {
        String json = """
                {"type":"AGENT_ERROR","sessionId":"sess-5","errorMessage":"oops","errorType":"RATE_LIMITED","timestamp":123}
                """;

        AgentEvent event = mapper.readValue(json, AgentEvent.class);

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(event.errorType()).isEqualTo("RATE_LIMITED");
        assertThat(event.errorMessage()).isEqualTo("oops");
    }

    @Test
    void error_event_deserializes_without_errorType_backward_compat() throws Exception {
        String json = """
                {"type":"AGENT_ERROR","sessionId":"sess-6","errorMessage":"legacy error","timestamp":456}
                """;

        AgentEvent event = mapper.readValue(json, AgentEvent.class);

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.AGENT_ERROR);
        assertThat(event.errorMessage()).isEqualTo("legacy error");
        assertThat(event.errorType()).isNull();
    }
}
