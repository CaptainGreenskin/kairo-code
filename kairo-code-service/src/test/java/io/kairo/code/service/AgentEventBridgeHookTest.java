package io.kairo.code.service;

import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.tool.ToolResult;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventBridgeHookTest {

    private Sinks.Many<AgentEvent> sink;
    private AgentEventBridgeHook hook;

    @BeforeEach
    void setUp() {
        sink = Sinks.many().multicast().onBackpressureBuffer();
        hook = new AgentEventBridgeHook(sink, "test-session");
    }

    @Test
    void postReasoningWithTextContentEmitsTextChunk() {
        var collector = sink.asFlux().collectList().toFuture();

        PostReasoningEvent event = eventWithText("hello world");
        hook.onPostReasoning(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.TEXT_CHUNK);
        assertThat(events.get(0).content()).isEqualTo("hello world");
    }

    @Test
    void postReasoningWithToolUseEmitsToolCall() {
        var collector = sink.asFlux().collectList().toFuture();

        PostReasoningEvent event = eventWithTools("bash");
        hook.onPostReasoning(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(1);
        AgentEvent e = events.get(0);
        assertThat(e.type()).isEqualTo(AgentEvent.EventType.TOOL_CALL);
        assertThat(e.toolName()).isEqualTo("bash");
        assertThat(e.toolCallId()).isEqualTo("id-bash");
        assertThat(e.requiresApproval()).isTrue();
    }

    @Test
    void postReasoningWithToolUseEmitsToolCallNoApprovalForSafeTools() {
        var collector = sink.asFlux().collectList().toFuture();

        PostReasoningEvent event = eventWithTools("read_file");
        hook.onPostReasoning(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).requiresApproval()).isFalse();
    }

    @Test
    void postReasoningWithTextAndToolEmitsBoth() {
        var collector = sink.asFlux().collectList().toFuture();

        List<Content> contents = List.of(
                new Content.TextContent("Let me check the file"),
                new Content.ToolUseContent("id-read", "read_file", Map.of("path", "foo.java")));
        ModelResponse response = new ModelResponse("r1", contents, null,
                ModelResponse.StopReason.TOOL_USE, "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        hook.onPostReasoning(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.TEXT_CHUNK);
        assertThat(events.get(0).content()).isEqualTo("Let me check the file");
        assertThat(events.get(1).type()).isEqualTo(AgentEvent.EventType.TOOL_CALL);
    }

    @Test
    void postActingEmitsToolResult() {
        var collector = sink.asFlux().collectList().toFuture();

        ToolResult result = new ToolResult("id-bash", "output content", false, Map.of());
        PostActingEvent event = new PostActingEvent("bash", result);
        hook.onPostActing(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.TOOL_RESULT);
        assertThat(events.get(0).toolCallId()).isEqualTo("id-bash");
        assertThat(events.get(0).toolResult()).isEqualTo("output content");
    }

    @Test
    void onToolResultEmitsToolResult() {
        var collector = sink.asFlux().collectList().toFuture();

        ToolResult result = new ToolResult("id-write", "wrote 5 lines", false, Map.of());
        ToolResultEvent event = new ToolResultEvent("write_file", result,
                java.time.Duration.ofMillis(100), true);
        hook.onToolResult(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentEvent.EventType.TOOL_RESULT);
        assertThat(events.get(0).toolCallId()).isEqualTo("id-write");
    }

    @Test
    void requiresApprovalForWriteAndBashTools() {
        var collector = sink.asFlux().collectList().toFuture();

        List<Content> contents = List.of(
                new Content.ToolUseContent("id-bash", "bash", Map.of()),
                new Content.ToolUseContent("id-write", "write_file", Map.of()),
                new Content.ToolUseContent("id-edit", "edit_file", Map.of()),
                new Content.ToolUseContent("id-read", "read_file", Map.of()),
                new Content.ToolUseContent("id-grep", "grep", Map.of()));
        ModelResponse response = new ModelResponse("r1", contents, null,
                ModelResponse.StopReason.TOOL_USE, "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        hook.onPostReasoning(event);
        sink.tryEmitComplete();

        List<AgentEvent> events = collector.join();
        assertThat(events).hasSize(5);
        assertThat(events.get(0).requiresApproval()).isTrue();  // bash
        assertThat(events.get(1).requiresApproval()).isTrue();  // write_file
        assertThat(events.get(2).requiresApproval()).isTrue();  // edit_file
        assertThat(events.get(3).requiresApproval()).isFalse(); // read_file
        assertThat(events.get(4).requiresApproval()).isFalse(); // grep
    }

    private static PostReasoningEvent eventWithText(String text) {
        ModelResponse response = new ModelResponse("r1",
                List.of(new Content.TextContent(text)),
                null, ModelResponse.StopReason.END_TURN, "gpt-4o");
        return new PostReasoningEvent(response, false);
    }

    private static PostReasoningEvent eventWithTools(String... toolNames) {
        List<Content> contents = new java.util.ArrayList<>();
        for (String name : toolNames) {
            contents.add(new Content.ToolUseContent("id-" + name, name, Map.of()));
        }
        ModelResponse response = new ModelResponse("r1", contents, null,
                ModelResponse.StopReason.TOOL_USE, "gpt-4o");
        return new PostReasoningEvent(response, false);
    }
}
