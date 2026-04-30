package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.skill.DefaultSkillRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillTabCompletionTest {

    private CommandRegistry commandRegistry;
    private SkillRegistry skillRegistry;
    private ReplCompleter completer;

    @BeforeEach
    void setUp() {
        commandRegistry = new CommandRegistry();
        commandRegistry.register(stubCommand("help"));
        commandRegistry.register(stubCommand("skill"));
        commandRegistry.register(stubCommand("clear"));
        commandRegistry.register(stubCommand("exit"));

        skillRegistry = new DefaultSkillRegistry();
        skillRegistry.register(skillDef("code-review", "Review code", SkillCategory.CODE));
        skillRegistry.register(skillDef("test-writer", "Write tests", SkillCategory.TESTING));
        skillRegistry.register(skillDef("deploy-helper", "Deploy stuff", SkillCategory.DEVOPS));

        completer = new ReplCompleter(commandRegistry, () -> skillRegistry);
    }

    @Test
    void topLevelCompletesAllCommands() {
        List<Candidate> candidates = complete(":");

        List<String> values = candidateValues(candidates);
        assertThat(values).contains(":help", ":skill", ":clear", ":exit");
    }

    @Test
    void partialCommandNameCompletes() {
        List<Candidate> candidates = complete(":sk");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactly(":skill");
    }

    @Test
    void skillSubcommandCompletes() {
        List<Candidate> candidates = complete(":skill ");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactlyInAnyOrder("list", "loaded", "load", "unload", "info");
    }

    @Test
    void skillSubcommandPartialCompletes() {
        List<Candidate> candidates = complete(":skill lo");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactlyInAnyOrder("load", "loaded");
    }

    @Test
    void skillLoadCompletesWithSkillNames() {
        List<Candidate> candidates = complete(":skill load ");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactlyInAnyOrder("code-review", "test-writer", "deploy-helper");
    }

    @Test
    void skillInfoCompletesWithSkillNames() {
        List<Candidate> candidates = complete(":skill info ");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactlyInAnyOrder("code-review", "test-writer", "deploy-helper");
    }

    @Test
    void skillUnloadCompletesWithSkillNames() {
        List<Candidate> candidates = complete(":skill unload ");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactlyInAnyOrder("code-review", "test-writer", "deploy-helper");
    }

    @Test
    void skillLoadPartialCompletesFilteredNames() {
        List<Candidate> candidates = complete(":skill load co");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactly("code-review");
    }

    @Test
    void skillListCategoryCompletes() {
        List<Candidate> candidates = complete(":skill list ");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactlyInAnyOrder(
                "code", "devops", "data", "testing", "documentation", "general");
    }

    @Test
    void skillListPartialCategoryCompletes() {
        List<Candidate> candidates = complete(":skill list de");

        List<String> values = candidateValues(candidates);
        assertThat(values).containsExactly("devops");
    }

    @Test
    void dynamicSkillNamesReflectRegistryChanges() {
        // Initially 3 skills
        List<Candidate> before = complete(":skill load ");
        assertThat(candidateValues(before)).hasSize(3);

        // Register a new skill
        skillRegistry.register(skillDef("refactor", "Refactor code", SkillCategory.CODE));

        List<Candidate> after = complete(":skill load ");
        assertThat(candidateValues(after)).hasSize(4);
        assertThat(candidateValues(after)).contains("refactor");
    }

    @Test
    void noCompletionForPlainText() {
        List<Candidate> candidates = complete("hello");

        assertThat(candidates).isEmpty();
    }

    @Test
    void nullSkillRegistryDoesNotCrash() {
        ReplCompleter nullRegistryCompleter = new ReplCompleter(commandRegistry, () -> null);

        List<Candidate> candidates = new ArrayList<>();
        nullRegistryCompleter.complete(null, parsedLine(":skill load "), candidates);

        assertThat(candidates).isEmpty();
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private List<Candidate> complete(String input) {
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, parsedLine(input), candidates);
        return candidates;
    }

    private static List<String> candidateValues(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::value).toList();
    }

    private static ParsedLine parsedLine(String line) {
        return new ParsedLine() {
            @Override public String word() { return ""; }
            @Override public int wordCursor() { return 0; }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }

    private static SlashCommand stubCommand(String name) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " command"; }
            @Override public void execute(String args, ReplContext context) {}
        };
    }

    private static SkillDefinition skillDef(String name, String desc, SkillCategory category) {
        return new SkillDefinition(
                name, "1.0.0", desc,
                "# " + name + "\n\nInstructions",
                List.of(), category, null, null, null, 0, null);
    }
}
