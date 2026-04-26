package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.util.List;

/** Lists the hook handlers currently registered in this session. */
public class HooksCommand implements SlashCommand {

    @Override
    public String name() {
        return "hooks";
    }

    @Override
    public String description() {
        return "List registered hook handlers";
    }

    @Override
    public void execute(String args, ReplContext context) {
        List<Object> handlers = context.hookHandlers();
        if (handlers.isEmpty()) {
            context.writer().println("No hook handlers registered.");
        } else {
            context.writer().println("Hook handlers (" + handlers.size() + "):");
            for (Object h : handlers) {
                context.writer().println("  " + h.getClass().getSimpleName());
            }
        }
        context.writer().flush();
    }
}
