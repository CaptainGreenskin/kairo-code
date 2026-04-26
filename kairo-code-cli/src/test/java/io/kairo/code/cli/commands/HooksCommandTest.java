package io.kairo.code.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentState;
import io.kairo.api.message.Msg;
import io.kairo.code.cli.CommandRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class HooksCommandTest {

    private StringWriter output;
    private PrintWriter writer;
    private HooksCommand command;
    private CodeAgentConfig config;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        writer = new PrintWriter(output, true);
        command = new HooksCommand();
        config = new CodeAgentConfig("key", "https://api.test.com", "gpt-4o", 50, "/tmp");
    }

    @Test
    void commandNameIsHooks() {
        assertThat(command.name()).isEqualTo("hooks");
    }

    @Test
    void noHandlersRegistered_printsEmptyMessage() {
        ReplContext context = createContext();
        command.execute("", context);

        assertThat(output.toString()).contains("No hook handlers registered");
    }

    @Test
    void singleHandlerRegistered_printsClassName() {
        ReplContext context = createContext();
        Object handler = new Object() {};
        context.setHookHandlers(List.of(handler));

        command.execute("", context);

        assertThat(output.toString()).contains("Hook handlers (1)");
    }

    @Test
    void multipleHandlersRegistered_printsAllClassNames() {
        ReplContext context = createContext();

        class FirstHandler {}
        class SecondHandler {}

        context.setHookHandlers(List.of(new FirstHandler(), new SecondHandler()));

        command.execute("", context);

        String out = output.toString();
        assertThat(out).contains("Hook handlers (2)");
        assertThat(out).contains("FirstHandler");
        assertThat(out).contains("SecondHandler");
    }

    @Test
    void nullHandlerList_treatedAsEmpty() {
        ReplContext context = createContext();
        context.setHookHandlers(null);

        command.execute("", context);

        assertThat(output.toString()).contains("No hook handlers registered");
    }

    private ReplContext createContext() {
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        DefaultToolExecutor toolExecutor =
                new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());
        Agent agent =
                new Agent() {
                    @Override
                    public Mono<Msg> call(Msg input) {
                        return Mono.empty();
                    }

                    @Override
                    public String id() {
                        return "test";
                    }

                    @Override
                    public String name() {
                        return "test-agent";
                    }

                    @Override
                    public AgentState state() {
                        return AgentState.IDLE;
                    }

                    @Override
                    public void interrupt() {}
                };
        CodeAgentSession session =
                new CodeAgentSession(agent, toolExecutor, toolRegistry, Set.of());
        CommandRegistry registry = new CommandRegistry();
        registry.register(command);
        return new ReplContext(session, config, null, registry, writer, null, null, null, null);
    }
}
