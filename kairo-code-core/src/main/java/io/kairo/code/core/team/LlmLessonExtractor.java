package io.kairo.code.core.team;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.team.TeamResult.StepOutcome;
import io.kairo.multiagent.orchestration.ExpertMemoryEntry;
import io.kairo.multiagent.orchestration.LessonExtractor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-backed {@link LessonExtractor}: asks the model to distill durable lessons from a role's step
 * outcomes at team completion. Produces {@link ExpertMemoryEntry} records for cross-task recall.
 *
 * <p>Extraction is best-effort — parse failures or empty model responses yield an empty list, never
 * an error, so lesson extraction can never break the main team result.
 */
public class LlmLessonExtractor implements LessonExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmLessonExtractor.class);

    private static final int MAX_OUTCOME_CHARS = 6000;
    private static final int MAX_LESSONS = 5;
    private static final Pattern LESSON_LINE =
            Pattern.compile("^[-*]\\s*(.+)$", Pattern.MULTILINE);

    private final ModelProvider modelProvider;
    private final String modelName;

    private static final String EXTRACTION_PROMPT = """
            You are a lesson-extraction agent. Analyze the team execution outcomes below for the role '%s'.

            Goal of the execution: %s

            Extract 0-%d durable lessons this role learned — things worth remembering for future
            similar tasks. Focus on:
            - Effective approaches that worked (apply again)
            - Pitfalls or dead-ends encountered (avoid next time)
            - Project-specific conventions discovered
            - Tool/workflow insights

            Skip: trivial observations, things derivable from code, one-off details.

            Outcomes:
            %s

            Output each lesson as a bullet point (one line, '- ' prefix). Output NOTHING ELSE.
            If there is nothing worth remembering, output exactly: NONE
            """;

    public LlmLessonExtractor(ModelProvider modelProvider, String modelName) {
        this.modelProvider = modelProvider;
        this.modelName = modelName;
    }

    @Override
    public reactor.core.publisher.Mono<List<ExpertMemoryEntry>> extract(
            String roleId, List<StepOutcome> roleOutcomes, String goal) {
        if (roleOutcomes == null || roleOutcomes.isEmpty()) {
            return reactor.core.publisher.Mono.just(List.of());
        }

        String outcomesText = buildOutcomesText(roleOutcomes);
        String prompt = String.format(EXTRACTION_PROMPT, roleId, goal, MAX_LESSONS, outcomesText);

        List<Msg> messages = List.of(Msg.of(MsgRole.USER, prompt));
        ModelConfig config =
                ModelConfig.builder().model(modelName).maxTokens(800).temperature(0.0).build();

        return modelProvider
                .call(messages, config)
                .map(response -> parseLessons(response, roleId))
                .onErrorResume(
                        e -> {
                            log.debug("Lesson extraction LLM call failed for {}: {}", roleId, e.getMessage());
                            return reactor.core.publisher.Mono.just(List.of());
                        });
    }

    private String buildOutcomesText(List<StepOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        for (StepOutcome o : outcomes) {
            String output = o.output() == null ? "(no output)" : o.output();
            if (output.length() > MAX_OUTCOME_CHARS) {
                output = output.substring(0, MAX_OUTCOME_CHARS) + "...(truncated)";
            }
            sb.append("## Step ").append(o.stepId()).append(":\n").append(output).append("\n\n");
        }
        return sb.toString();
    }

    private List<ExpertMemoryEntry> parseLessons(ModelResponse response, String roleId) {
        if (response == null || response.contents() == null) {
            return List.of();
        }
        String text =
                response.contents().stream()
                        .filter(Content.TextContent.class::isInstance)
                        .map(c -> ((Content.TextContent) c).text())
                        .reduce("", String::concat);

        if (text.contains("NONE")) {
            return List.of();
        }

        List<ExpertMemoryEntry> lessons = new ArrayList<>();
        Matcher m = LESSON_LINE.matcher(text);
        while (m.find() && lessons.size() < MAX_LESSONS) {
            String lesson = m.group(1).trim();
            if (lesson.length() > 10 && lesson.length() < 500) {
                lessons.add(
                        new ExpertMemoryEntry(
                                roleId, roleId, lesson, Instant.now(), 0.7));
            }
        }
        log.debug("Extracted {} lessons for role {}", lessons.size(), roleId);
        return lessons;
    }
}
