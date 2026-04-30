package io.kairo.code.cli.commands;

import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs diagnostics and health checks for the kairo-code environment.
 *
 * <p>Usage: {@code :doctor}
 */
public class DoctorCommand implements SlashCommand {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "Run diagnostics and health checks";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        writer.println("kairo-code diagnostics");
        writer.println("\u2500".repeat(40));

        // 1. API Key
        String apiKey = context.config().apiKey();
        boolean hasApiKey = apiKey != null && !apiKey.isBlank();
        printCheck(writer, "API Key configured", hasApiKey);

        // 2. JDK version
        int jdkVersion = Runtime.version().feature();
        printCheck(writer, "JDK " + jdkVersion + " (\u226517 required)", jdkVersion >= 17);

        // 3. Git on PATH
        printCheck(writer, "git on PATH", checkCommand("git", "--version"));

        // 4. Maven on PATH
        printCheck(writer, "mvn on PATH", checkCommand("mvn", "--version"));

        // 5. .kairo-code/ initialized
        String workDir = context.config().workingDir();
        Path workDirPath = workDir != null && !workDir.isBlank()
                ? Path.of(workDir)
                : Path.of(System.getProperty("user.dir"));
        Path kairoDir = workDirPath.resolve(".kairo-code");
        printCheck(writer, ".kairo-code/ initialized", Files.isDirectory(kairoDir));

        // 6. Memory store writable
        Path memPath = kairoDir.resolve("memory");
        printCheck(writer, "Memory store writable",
                Files.isDirectory(memPath) && Files.isWritable(memPath));

        // 7. Skills loaded count
        SkillRegistry skillRegistry = context.skillRegistry();
        if (skillRegistry != null) {
            int count = skillRegistry.list().size();
            printCheck(writer, "Skills registered: " + count, count > 0);
        } else {
            printCheck(writer, "Skills registry", false);
        }

        // 8. Model connectivity — skipped in command context (no lightweight ping available).
        // Using .block(Duration) on a model call would require a provider instance which may
        // not be configured. Print skip note instead.
        writer.println("  - Model connectivity: skipped (use :compact to verify model access)");

        // 9. Version
        writer.println("  kairo-code version: " + VERSION);

        writer.flush();
    }

    private void printCheck(PrintWriter writer, String label, boolean ok) {
        writer.println("  " + (ok ? "\u2713" : "\u2717") + " " + label);
    }

    private boolean checkCommand(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            // Consume output to prevent blocking
            p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
