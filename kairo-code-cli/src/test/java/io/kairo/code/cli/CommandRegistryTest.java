package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {

    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
    }

    @Test
    void registerAndResolveByColonPrefix() {
        registry.register(stubCommand("help", "List commands"));

        Optional<SlashCommand> result = registry.resolve(":help");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("help");
    }

    @Test
    void registerAndResolveBySlashPrefix() {
        registry.register(stubCommand("help", "List commands"));

        Optional<SlashCommand> result = registry.resolve("/help");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("help");
    }

    @Test
    void resolveIsCaseInsensitive() {
        registry.register(stubCommand("Help", "List commands"));

        assertThat(registry.resolve(":help")).isPresent();
        assertThat(registry.resolve(":HELP")).isPresent();
    }

    @Test
    void resolveReturnsEmptyForUnknownCommand() {
        registry.register(stubCommand("help", "List commands"));

        assertThat(registry.resolve(":unknown")).isEmpty();
    }

    @Test
    void resolveReturnsEmptyForNonCommand() {
        registry.register(stubCommand("help", "List commands"));

        assertThat(registry.resolve("hello world")).isEmpty();
    }

    @Test
    void resolveReturnsEmptyForBlankInput() {
        assertThat(registry.resolve("")).isEmpty();
        assertThat(registry.resolve("  ")).isEmpty();
        assertThat(registry.resolve(null)).isEmpty();
    }

    @Test
    void extractArgsReturnsTextAfterCommand() {
        assertThat(CommandRegistry.extractArgs(":model gpt-4o")).isEqualTo("gpt-4o");
        assertThat(CommandRegistry.extractArgs("/model gpt-4o mini")).isEqualTo("gpt-4o mini");
    }

    @Test
    void extractArgsReturnsEmptyWhenNoArgs() {
        assertThat(CommandRegistry.extractArgs(":help")).isEmpty();
        assertThat(CommandRegistry.extractArgs(null)).isEmpty();
    }

    @Test
    void allCommandsReturnsRegistrationOrder() {
        registry.register(stubCommand("help", "Help"));
        registry.register(stubCommand("clear", "Clear"));
        registry.register(stubCommand("exit", "Exit"));

        assertThat(registry.allCommands())
                .extracting(SlashCommand::name)
                .containsExactly("help", "clear", "exit");
    }

    @Test
    void allCommandNamesMatchesRegisteredCommands() {
        registry.register(stubCommand("help", "Help"));
        registry.register(stubCommand("exit", "Exit"));

        assertThat(registry.allCommandNames()).containsExactly("help", "exit");
    }

    @Test
    void registerRejectsNull() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveWithArgsStillFindsCommand() {
        registry.register(stubCommand("model", "Set model"));

        Optional<SlashCommand> result = registry.resolve(":model gpt-4o");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("model");
    }

    private static SlashCommand stubCommand(String name, String description) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public void execute(String args, ReplContext context) {
                // no-op stub
            }
        };
    }
}
