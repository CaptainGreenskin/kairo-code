package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.evolution.LearnedLessonStore.Lesson;
import io.kairo.code.core.evolution.LearnedLessonStore.Status;
import java.nio.file.Path;
import java.util.List;

/**
 * Manage learned lessons that are injected into the agent's system prompt.
 *
 * <p>Subcommands: {@code list}, {@code approve <id>}, {@code reject <id>}, {@code add <tool>
 * <text>}, {@code clear}.
 */
public class LearnedCommand implements SlashCommand {

    @Override
    public String name() {
        return "learned";
    }

    @Override
    public String description() {
        return "Manage learned lessons (:learned list|approve|reject|add|clear)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        var writer = context.writer();
        LearnedLessonStore store = globalStore();
        String trimmed = args == null ? "" : args.trim();

        if (trimmed.isEmpty() || trimmed.equals("list")) {
            doList(store, writer);
        } else if (trimmed.startsWith("approve ")) {
            doApprove(store, trimmed.substring(8).trim(), writer);
        } else if (trimmed.startsWith("reject ")) {
            doReject(store, trimmed.substring(7).trim(), writer);
        } else if (trimmed.startsWith("add ")) {
            doAdd(store, trimmed.substring(4).trim(), writer);
        } else if (trimmed.equals("clear")) {
            doClear(store, writer);
        } else {
            writer.println("Usage: :learned [list|approve <id>|reject <id>|add <tool> <text>|clear]");
        }
        writer.flush();
    }

    private void doList(LearnedLessonStore store, java.io.PrintWriter writer) {
        List<Lesson> lessons = store.list();
        if (lessons.isEmpty()) {
            writer.println("No learned lessons. Add one with :learned add <tool> <text>");
            return;
        }
        writer.println();
        writer.printf("%-8s %-10s %-10s %s%n", "ID", "Status", "Tool", "Lesson");
        writer.println("-".repeat(70));
        for (Lesson l : lessons) {
            String status = l.status().name();
            String text = l.lessonText().length() > 45
                    ? l.lessonText().substring(0, 42) + "..."
                    : l.lessonText();
            writer.printf("%-8s %-10s %-10s %s%n", l.id(), status, l.toolName(), text);
        }
        writer.println();
    }

    private void doApprove(LearnedLessonStore store, String id, java.io.PrintWriter writer) {
        if (id.isEmpty()) {
            writer.println("Usage: :learned approve <id>");
            return;
        }
        if (store.approve(id)) {
            writer.println("Lesson " + id + " approved — will be injected into future sessions.");
        } else {
            writer.println("No lesson found with id: " + id);
        }
    }

    private void doReject(LearnedLessonStore store, String id, java.io.PrintWriter writer) {
        if (id.isEmpty()) {
            writer.println("Usage: :learned reject <id>");
            return;
        }
        if (store.reject(id)) {
            writer.println("Lesson " + id + " rejected — will not be injected.");
        } else {
            writer.println("No lesson found with id: " + id);
        }
    }

    private void doAdd(LearnedLessonStore store, String rest, java.io.PrintWriter writer) {
        int space = rest.indexOf(' ');
        if (space < 0) {
            writer.println("Usage: :learned add <tool> <lesson text>");
            return;
        }
        String toolName = rest.substring(0, space).trim();
        String text = rest.substring(space + 1).trim();
        if (toolName.isEmpty() || text.isEmpty()) {
            writer.println("Usage: :learned add <tool> <lesson text>");
            return;
        }
        Lesson lesson = Lesson.create(toolName, text, Status.APPROVED);
        store.save(lesson);
        writer.println("Lesson saved (id=" + lesson.id() + ", status=APPROVED).");
    }

    private void doClear(LearnedLessonStore store, java.io.PrintWriter writer) {
        int removed = store.clearRejected();
        writer.println("Cleared " + removed + " rejected lesson(s).");
    }

    private static LearnedLessonStore globalStore() {
        return LearnedLessonStore.fromKairoDir(
                Path.of(System.getProperty("user.home"), ".kairo-code"));
    }
}
