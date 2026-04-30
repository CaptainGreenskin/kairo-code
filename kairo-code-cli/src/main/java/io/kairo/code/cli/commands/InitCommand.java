package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scaffolds project-local {@code .kairo-code/} directory structure.
 *
 * <p>Usage: {@code :init}
 */
public class InitCommand implements SlashCommand {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String description() {
        return "Initialize kairo-code in current project";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        String workDir = context.config().workingDir();
        Path workDirPath = workDir != null && !workDir.isBlank()
                ? Path.of(workDir)
                : Path.of(System.getProperty("user.dir"));
        Path kairoDir = workDirPath.resolve(".kairo-code");

        if (Files.isDirectory(kairoDir)) {
            writer.println("Already initialized: " + kairoDir);
            writer.flush();
            return;
        }

        // Warn if not a git repo (but don't fail)
        if (!Files.isDirectory(workDirPath.resolve(".git"))) {
            writer.println("\u26a0 Warning: Not a git repository. Some features require git.");
        }

        try {
            Files.createDirectories(kairoDir.resolve("skills"));
            Files.createDirectories(kairoDir.resolve("memory"));
            Files.createDirectories(kairoDir.resolve("sessions"));

            Files.writeString(kairoDir.resolve("config.properties"),
                    "# kairo-code project configuration\n"
                            + "# model=gpt-4o\n"
                            + "# max-iterations=50\n");

            Files.writeString(kairoDir.resolve("hooks.json"), "{}");

            writer.println("Initialized kairo-code at " + kairoDir);
            writer.println("  Created: skills/, memory/, sessions/");
            writer.println("  Created: config.properties, hooks.json");
        } catch (IOException e) {
            writer.println("Failed to initialize: " + e.getMessage());
        }
        writer.flush();
    }
}
