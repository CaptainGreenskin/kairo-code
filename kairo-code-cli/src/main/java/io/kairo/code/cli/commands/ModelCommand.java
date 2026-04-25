package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;

/**
 * Shows or switches the current model.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :model} — print current model name</li>
 *   <li>{@code :model gpt-4o-mini} — switch to the specified model</li>
 * </ul>
 */
public class ModelCommand implements SlashCommand {

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Show or switch the current model";
    }

    @Override
    public void execute(String args, ReplContext context) {
        var writer = context.writer();

        if (args == null || args.isBlank()) {
            writer.println("Current model: " + context.modelName());
        } else {
            String newModel = args.trim();
            context.setModelName(newModel);
            writer.println("✓ Switched to model: " + newModel);
        }
        writer.flush();
    }
}
