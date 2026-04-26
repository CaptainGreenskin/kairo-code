package io.kairo.code.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes task execution results to dated log files when KAIRO_CODE_LOG_DIR is set. */
public class TaskExecutionLogger {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SUMMARY_MAX_CHARS = 500;

    public enum Status {
        SUCCESS, FAILED, TIMEOUT
    }

    private final Path logDir;

    private TaskExecutionLogger(Path logDir) {
        this.logDir = logDir;
    }

    /** Returns a logger if KAIRO_CODE_LOG_DIR is set, otherwise empty (silent mode). */
    public static TaskExecutionLogger fromEnv() {
        String dir = System.getenv("KAIRO_CODE_LOG_DIR");
        if (dir == null || dir.isBlank()) {
            return new TaskExecutionLogger(null);
        }
        return new TaskExecutionLogger(Path.of(dir));
    }

    /** Visible for testing. */
    static TaskExecutionLogger forDirectory(Path dir) {
        return new TaskExecutionLogger(dir);
    }

    public boolean isEnabled() {
        return logDir != null;
    }

    public void write(LogEntry entry) {
        if (!isEnabled()) return;
        try {
            Path dated = logDir.resolve(entry.startTime().format(DATE_DIR));
            Files.createDirectories(dated);
            Path file = dated.resolve(entry.startTime().format(FILE_TIME) + "-task.md");
            Files.writeString(file, render(entry), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[logger] Failed to write task log: " + e.getMessage());
        }
    }

    private String render(LogEntry e) {
        Duration elapsed = Duration.between(e.startTime(), e.endTime());
        long minutes = elapsed.toMinutes();
        long seconds = elapsed.minusMinutes(minutes).getSeconds();

        String summary = e.agentOutput() == null ? "(no output)"
                : e.agentOutput().length() > SUMMARY_MAX_CHARS
                        ? e.agentOutput().substring(0, SUMMARY_MAX_CHARS) + "..."
                        : e.agentOutput();

        StringBuilder sb = new StringBuilder();
        sb.append("# Task Execution Log\n\n");
        sb.append("- 开始时间：").append(e.startTime().format(ISO)).append("\n");
        sb.append("- 结束时间：").append(e.endTime().format(ISO)).append("\n");
        sb.append("- 耗时：").append(minutes).append("m").append(seconds).append("s\n");
        sb.append("- 状态：").append(e.status()).append("\n");
        if (e.taskSource() != null) {
            sb.append("- 任务来源：").append(e.taskSource()).append("\n");
        }
        sb.append("\n## 任务描述\n\n").append(e.taskDescription()).append("\n");
        sb.append("\n## 执行摘要\n\n").append(summary).append("\n");
        sb.append("\n## 工具调用统计\n\n");
        sb.append("- 总调用次数：").append(e.toolCallCount()).append("\n");
        sb.append("- 成功：").append(e.toolSuccessCount()).append("\n");
        sb.append("- 失败：").append(e.toolCallCount() - e.toolSuccessCount()).append("\n");
        if (e.errorMessage() != null) {
            sb.append("\n## 错误信息\n\n").append(e.errorMessage()).append("\n");
        }
        return sb.toString();
    }

    public record LogEntry(
            LocalDateTime startTime,
            LocalDateTime endTime,
            Status status,
            String taskSource,
            String taskDescription,
            String agentOutput,
            int toolCallCount,
            int toolSuccessCount,
            String errorMessage) {

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private LocalDateTime startTime = LocalDateTime.now();
            private LocalDateTime endTime;
            private Status status = Status.SUCCESS;
            private String taskSource;
            private String taskDescription = "";
            private String agentOutput;
            private int toolCallCount;
            private int toolSuccessCount;
            private String errorMessage;

            public Builder startTime(LocalDateTime v) { startTime = v; return this; }
            public Builder endTime(LocalDateTime v) { endTime = v; return this; }
            public Builder status(Status v) { status = v; return this; }
            public Builder taskSource(String v) { taskSource = v; return this; }
            public Builder taskDescription(String v) { taskDescription = v; return this; }
            public Builder agentOutput(String v) { agentOutput = v; return this; }
            public Builder toolCallCount(int v) { toolCallCount = v; return this; }
            public Builder toolSuccessCount(int v) { toolSuccessCount = v; return this; }
            public Builder errorMessage(String v) { errorMessage = v; return this; }

            public LogEntry build() {
                if (endTime == null) endTime = LocalDateTime.now();
                return new LogEntry(startTime, endTime, status, taskSource,
                        taskDescription, agentOutput, toolCallCount, toolSuccessCount, errorMessage);
            }
        }
    }
}
