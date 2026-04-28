package io.kairo.code.core.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ConversationCompactorTest {

    @Test
    void compactsNormalHistoryReturnsSummary() {
        List<Msg> history = buildHistory(10);
        StubModelProvider provider = new StubModelProvider("summary of the conversation");

        Mono<String> result = ConversationCompactor.compact(history, provider, "gpt-4o");

        assertThat(result.block()).isEqualTo("summary of the conversation");
    }

    @Test
    void providerFailurePropagatesAsMonoError() {
        List<Msg> history = buildHistory(10);
        FailingModelProvider provider = new FailingModelProvider();

        Mono<String> result = ConversationCompactor.compact(history, provider, "gpt-4o");

        assertThatThrownBy(() -> result.block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model provider failure");
    }

    @Test
    void emptyHistoryReturnsError() {
        StubModelProvider provider = new StubModelProvider("irrelevant");

        assertThatThrownBy(() -> ConversationCompactor.compact(List.of(), provider, "gpt-4o").block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullHistoryReturnsError() {
        StubModelProvider provider = new StubModelProvider("irrelevant");

        assertThatThrownBy(() -> ConversationCompactor.compact(null, provider, "gpt-4o").block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesToMax50Messages() {
        // Build 80 messages — compactor should only use latest 50 (indices 30-79)
        List<Msg> history = buildHistory(80);
        TrackingModelProvider provider = new TrackingModelProvider("summary");

        ConversationCompactor.compact(history, provider, "gpt-4o").block();

        // The user message content should reference message 31 (index 30) onwards,
        // not message 1. Verify the serialized text contains later messages only.
        assertThat(provider.receivedMessages).isNotNull();
        String userContent = provider.receivedMessages.stream()
                .filter(m -> m.role() == MsgRole.USER)
                .map(Msg::text)
                .findFirst()
                .orElse("");
        assertThat(userContent).contains("message 30");
        assertThat(userContent).doesNotContain("message 0");
    }

    @Test
    void handlesSingleMessageHistory() {
        List<Msg> history = List.of(Msg.of(MsgRole.USER, "hello"));
        StubModelProvider provider = new StubModelProvider("user said hello");

        Mono<String> result = ConversationCompactor.compact(history, provider, "gpt-4o");

        assertThat(result.block()).isEqualTo("user said hello");
    }

    /** Build N alternating USER/ASSISTANT messages. */
    private static List<Msg> buildHistory(int n) {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MsgRole role = i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT;
            messages.add(Msg.of(role, "message " + i));
        }
        return messages;
    }

    static class StubModelProvider implements ModelProvider {
        private final String response;

        StubModelProvider(String response) {
            this.response = response;
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent(response));
            return Mono.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent(response));
            return Flux.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    static class FailingModelProvider implements ModelProvider {
        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            return Mono.error(new RuntimeException("model provider failure"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            return Flux.error(new RuntimeException("model provider failure"));
        }

        @Override
        public String name() {
            return "failing";
        }
    }

    static class TrackingModelProvider implements ModelProvider {
        private final String response;
        List<Msg> receivedMessages;

        TrackingModelProvider(String response) {
            this.response = response;
        }

        @Override
        public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
            this.receivedMessages = messages;
            List<Content> contents = List.of(new Content.TextContent(response));
            return Mono.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
            List<Content> contents = List.of(new Content.TextContent(response));
            return Flux.just(new ModelResponse("stub-id", contents, null, null, "stub-model"));
        }

        @Override
        public String name() {
            return "tracking";
        }
    }
}
