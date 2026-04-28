package io.kairo.code.core.evolution;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.evolution.LearnedLessonStore.Lesson;
import io.kairo.code.core.evolution.LearnedLessonStore.Status;
import io.kairo.core.agent.AgentBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot reflection pipeline: when strike-3 fires, calls the LLM once (no tools)
 * to generate a concise lesson from the recent errors, and saves it as PENDING.
 */
public final class ReflectionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReflectionPipeline.class);

    private static final int MAX_LESSON_CHARS = 100;

    private static final Executor DAEMON_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reflection-pipeline");
        t.setDaemon(true);
        return t;
    });

    private ReflectionPipeline() {}

    /**
     * Asynchronously generate a lesson from the strike event and save it to the lesson store.
     *
     * @param event      the strike-3 event containing tool name and recent errors
     * @param config     the agent config (provides model provider credentials)
     * @param lessonStore where to persist the generated lesson
     */
    public static CompletableFuture<Void> generateAndSave(
            ToolStrikeEvent event, CodeAgentConfig config, LearnedLessonStore lessonStore) {
        return CompletableFuture.runAsync(() -> {
            try {
                ModelProvider provider = new io.kairo.core.model.openai.OpenAIProvider(
                        config.apiKey(), config.baseUrl());
                String lesson = generateLesson(event, provider, config.modelName());
                if (lesson != null && !lesson.isBlank()) {
                    Lesson saved = Lesson.create(event.toolName(), lesson, Status.PENDING);
                    lessonStore.save(saved);
                    log.debug("Saved reflection lesson: [{}] {}", saved.toolName(), saved.lessonText());
                }
            } catch (Exception e) {
                log.debug("Reflection pipeline failed for tool '{}': {}", event.toolName(), e.getMessage());
            }
        }, DAEMON_EXECUTOR);
    }

    /**
     * Call the LLM once (no tools) to produce a one-line lesson from the errors.
     * Visible for testing with a stub ModelProvider.
     */
    static String generateLesson(ToolStrikeEvent event, ModelProvider provider, String modelName) {
        String errorsBlock = String.join("\n---\n", event.recentErrors());
        String userMsg = "以下是工具 '" + event.toolName() + "' 的最近 "
                + event.recentErrors().size() + " 次失败信息：\n"
                + "<errors>\n" + errorsBlock + "\n</errors>\n"
                + "请用一句话总结应避免什么，以防止此类失败（中文，30字以内）。只输出教训本身。";

        Agent reflectionAgent = AgentBuilder.create()
                .name("reflection")
                .model(provider)
                .modelName(modelName)
                .systemPrompt("You are a concise code review assistant. "
                        + "Respond with exactly one short sentence in Chinese.")
                .maxIterations(1)
                .build();

        Msg response;
        try {
            response = reflectionAgent.call(Msg.of(MsgRole.USER, userMsg)).block();
        } catch (Exception e) {
            return null;
        }
        if (response == null) {
            return null;
        }
        String text = response.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        // Trim to max characters
        return text.length() > MAX_LESSON_CHARS ? text.substring(0, MAX_LESSON_CHARS) : text.trim();
    }
}
