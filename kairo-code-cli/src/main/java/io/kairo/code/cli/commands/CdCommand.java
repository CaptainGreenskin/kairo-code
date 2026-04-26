package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.nio.file.Files;
import java.nio.file.Path;

/** Changes the Agent's working directory and rebuilds the session. */
public class CdCommand implements SlashCommand {

    @Override
    public String name() {
        return "cd";
    }

    @Override
    public String description() {
        return "Change working directory (rebuilds session)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        if (args == null || args.isBlank()) {
            context.writer().println("Usage: :cd <path>");
            context.writer().flush();
            return;
        }
        Path newDir = Path.of(args.trim()).toAbsolutePath();
        if (!Files.isDirectory(newDir)) {
            context.writer().printf("Error: not a directory: %s%n", newDir);
            context.writer().flush();
            return;
        }
        context.setWorkingDir(newDir.toString());
        context.writer().printf("Working directory: %s%n", newDir);
        context.writer().flush();
    }
}
