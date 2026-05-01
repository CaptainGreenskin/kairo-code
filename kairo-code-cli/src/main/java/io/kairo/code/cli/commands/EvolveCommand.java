package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.cli.demo.SelfEvolutionDemo;

/**
 * REPL slash command that runs the self-evolution demo.
 *
 * <p>Usage: {@code :evolve}
 */
public class EvolveCommand implements SlashCommand {

    @Override
    public String name() {
        return "evolve";
    }

    @Override
    public String description() {
        return "Run self-evolution demo";
    }

    @Override
    public void execute(String args, ReplContext context) {
        SelfEvolutionDemo.run(context);
    }
}
