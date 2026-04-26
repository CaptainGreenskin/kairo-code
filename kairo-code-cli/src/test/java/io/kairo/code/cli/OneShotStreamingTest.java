package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Tests for streaming mode via StreamingAgentRunner (used in --verbose one-shot execution).
 */
class OneShotStreamingTest {

    private static Agent stubAgent(Mono<Msg> result) {
        return new Agent() {
            @Override
            public Mono<Msg> call(Msg input) {
                return result;
            }

            @Override
            public String id() {
                return "stub";
            }

            @Override
            public String name() {
                return "stub-agent";
            }

            @Override
            public AgentState state() {
                return AgentState.IDLE;
            }

            @Override
            public void interrupt() {}
        };
    }

    private static PrintWriter devNull() {
        return new PrintWriter(new ByteArrayOutputStream(), true);
    }

    @Test
    void returnsResponseFromAgent() {
        Msg expected = Msg.of(MsgRole.ASSISTANT, "hello from agent");
        Agent agent = stubAgent(Mono.just(expected));

        Msg result = new StreamingAgentRunner(devNull()).run(Msg.of(MsgRole.USER, "hi"), agent);

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("hello from agent");
    }

    @Test
    void returnsNullWhenMonoEmpty() {
        Agent agent = stubAgent(Mono.empty());

        Msg result = new StreamingAgentRunner(devNull()).run(Msg.of(MsgRole.USER, "hi"), agent);

        assertThat(result).isNull();
    }

    @Test
    void throwsAgentExecutionExceptionOnError() {
        Agent agent = stubAgent(Mono.error(new RuntimeException("model unavailable")));

        assertThrows(
                StreamingAgentRunner.AgentExecutionException.class,
                () -> new StreamingAgentRunner(devNull()).run(Msg.of(MsgRole.USER, "hi"), agent));
    }

    @Test
    void isNotRunningAfterCompletion() {
        Agent agent = stubAgent(Mono.just(Msg.of(MsgRole.ASSISTANT, "done")));
        StreamingAgentRunner runner = new StreamingAgentRunner(devNull());

        assertThat(runner.isRunning()).isFalse();
        runner.run(Msg.of(MsgRole.USER, "hi"), agent);
        assertThat(runner.isRunning()).isFalse();
    }
}
