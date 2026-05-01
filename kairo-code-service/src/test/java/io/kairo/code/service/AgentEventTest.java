package io.kairo.code.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventTest {

    @Test
    void contextCompactedHasCorrectTypeAndPayload() {
        AgentEvent event = AgentEvent.contextCompacted("session-1", 80_000, 100_000);

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.CONTEXT_COMPACTED);
        assertThat(event.sessionId()).isEqualTo("session-1");
        assertThat(event.tokenUsage()).isEqualTo(80_000L);
        assertThat(event.content())
                .contains("\"beforeTokens\":80000")
                .contains("\"maxTokens\":100000")
                .contains("\"ratio\":0.8000");
    }

    @Test
    void contextCompactedRatioClampedToOne() {
        // Pre-compaction tokens larger than max should still produce a sane ratio (1.0)
        AgentEvent event = AgentEvent.contextCompacted("s", 150_000, 100_000);

        assertThat(event.content()).contains("\"ratio\":1.0000");
    }

    @Test
    void contextCompactedHandlesZeroMaxTokensWithoutDivisionError() {
        AgentEvent event = AgentEvent.contextCompacted("s", 1_000, 0);

        assertThat(event.type()).isEqualTo(AgentEvent.EventType.CONTEXT_COMPACTED);
        assertThat(event.content()).contains("\"ratio\":0.0000");
    }

    @Test
    void contextCompactedHasTimestampAndNoToolFields() {
        AgentEvent event = AgentEvent.contextCompacted("s", 50_000, 100_000);

        assertThat(event.timestamp()).isPositive();
        assertThat(event.toolName()).isNull();
        assertThat(event.toolCallId()).isNull();
        assertThat(event.errorMessage()).isNull();
    }
}
