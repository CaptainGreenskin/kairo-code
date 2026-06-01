package io.kairo.code.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs multiple tasks in parallel from a JSON Lines task-list file.
 *
 * <p>File format (one JSON object per line):
 *
 * <pre>{"id":"task-1","task":"describe foo.java"}</pre>
 */
public final class ParallelTaskRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CodeAgentConfig config;
    private final ModelProvider modelProvider;
    private final int timeoutSeconds;
    private final PrintStream err;
    private final Path outputFile;

    public ParallelTaskRunner(
            CodeAgentConfig config,
            ModelProvider modelProvider,
            int timeoutSeconds,
            PrintStream err) {
        this(config, modelProvider, timeoutSeconds, err, null);
    }

    public ParallelTaskRunner(
            CodeAgentConfig config,
            ModelProvider modelProvider,
            int timeoutSeconds,
            PrintStream err,
            Path outputFile) {
        this.config = config;
        this.modelProvider = modelProvider;
        this.timeoutSeconds = timeoutSeconds;
        this.err = err;
        this.outputFile = outputFile;
    }

    /**
     * Reads {@code taskListFile}, runs all tasks concurrently, writes results.
     *
     * @return 0 if all tasks succeed, 1 on any failure (task error or file error)
     */
    public int run(Path taskListFile) {
        List<TaskEntry> entries;
        try {
            entries = parse(taskListFile);
        } catch (Exception e) {
            err.println("Error reading task-list: " + e.getMessage());
            return 1;
        }

        if (entries.isEmpty()) {
            err.println("[INFO] task-list is empty, nothing to run");
            return 0;
        }

        List<Mono<TaskResult>> monos = new ArrayList<>();
        for (TaskEntry entry : entries) {
            monos.add(runOne(entry).subscribeOn(Schedulers.boundedElastic()));
        }

        List<TaskResult> results = Flux.merge(Flux.fromIterable(monos))
                .collectList()
                .block();

        if (results == null) results = List.of();

        StringBuilder output = new StringBuilder();
        boolean anyFailed = false;
        for (TaskResult result : results) {
            output.append("=== ").append(result.id()).append(" ===\n");
            if (result.error() != null) {
                output.append("[ERROR] ").append(result.error()).append("\n");
                anyFailed = true;
            } else {
                output.append(result.response()).append("\n");
            }
            output.append("\n");
        }

        String text = output.toString();
        if (outputFile != null) {
            try {
                Files.writeString(outputFile, text, StandardCharsets.UTF_8);
            } catch (Exception e) {
                err.println("Failed to write output file: " + e.getMessage());
                System.out.print(text);
            }
        } else {
            System.out.print(text);
        }
        return anyFailed ? 1 : 0;
    }

    private Mono<TaskResult> runOne(TaskEntry entry) {
        return Mono.fromCallable(() -> {
            try {
                var agent = CodeAgentFactory.create(config, modelProvider);
                var mono = agent.call(Msg.of(MsgRole.USER, entry.task()));
                if (timeoutSeconds > 0) {
                    mono = mono.timeout(Duration.ofSeconds(timeoutSeconds));
                }
                Msg response = mono.block();
                String text = response != null ? response.text() : "(no response)";
                return new TaskResult(entry.id(), text, null);
            } catch (Exception e) {
                return new TaskResult(entry.id(), null, e.getMessage());
            }
        });
    }

    private static List<TaskEntry> parse(Path file) throws Exception {
        List<TaskEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String id = node.path("id").asText("task-" + (entries.size() + 1));
                String task = node.path("task").asText();
                if (task.isBlank()) {
                    continue;
                }
                entries.add(new TaskEntry(id, task));
            } catch (Exception e) {
                // skip malformed lines silently (logged by caller context)
            }
        }
        return entries;
    }

    record TaskEntry(String id, String task) {}

    record TaskResult(String id, String response, String error) {}
}
