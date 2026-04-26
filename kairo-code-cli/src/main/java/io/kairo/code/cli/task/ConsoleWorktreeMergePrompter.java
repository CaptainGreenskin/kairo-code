/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.cli.task;

import io.kairo.code.core.task.WorktreeMergeChoice;
import io.kairo.code.core.task.WorktreeMergePrompter;
import io.kairo.code.core.workspace.DiffStats;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Console-based merge prompter for the {@code task} tool. Asks the user whether to
 * {@link WorktreeMergeChoice#MERGE merge}, {@link WorktreeMergeChoice#DISCARD discard}, or
 * {@link WorktreeMergeChoice#KEEP keep} a child task's worktree.
 *
 * <p>Uses the same {@link BufferedReader}/{@link PrintWriter} channels as {@code
 * ConsoleApprovalHandler} (typically JLine-backed) so prompts integrate cleanly with the REPL.
 *
 * <p><b>Cancellation:</b> if the Reactor subscription is disposed (e.g., Ctrl+C), the prompting
 * thread is interrupted and the prompter returns {@link WorktreeMergeChoice#DISCARD} — the safest
 * default per the {@link WorktreeMergePrompter} contract.
 */
public final class ConsoleWorktreeMergePrompter implements WorktreeMergePrompter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleWorktreeMergePrompter.class);
    private static final long POLL_INTERVAL_MS = 50;

    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Object promptLock = new Object();

    public ConsoleWorktreeMergePrompter(BufferedReader reader, PrintWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    @Override
    public Mono<WorktreeMergeChoice> prompt(
            String taskId, String description, DiffStats stats, Path worktreePath) {
        return Mono.<WorktreeMergeChoice>create(sink -> {
                    Thread thread =
                            new Thread(
                                    () -> {
                                        try {
                                            sink.success(promptUser(taskId, description, stats, worktreePath));
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            sink.success(WorktreeMergeChoice.DISCARD);
                                        } catch (Exception e) {
                                            log.warn("Worktree merge prompt error", e);
                                            sink.success(WorktreeMergeChoice.DISCARD);
                                        }
                                    },
                                    "worktree-merge-prompt");
                    thread.setDaemon(true);
                    thread.start();
                    sink.onDispose(thread::interrupt);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private WorktreeMergeChoice promptUser(
            String taskId, String description, DiffStats stats, Path worktreePath)
            throws InterruptedException {
        synchronized (promptLock) {
            writer.println();
            writer.printf("✱ Sub-task %s finished: %s%n", taskId, description);
            writer.printf(
                    "  Worktree: %s  (%d file(s) changed, +%d/-%d)%n",
                    worktreePath, stats.filesChanged(), stats.insertions(), stats.deletions());
            writer.print("[m]erge / [d]iscard / [k]eep > ");
            writer.flush();

            String input;
            try {
                input = readLineInterruptibly(reader);
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException e) {
                log.warn("Failed to read merge prompt input", e);
                return WorktreeMergeChoice.DISCARD;
            }

            if (input == null) {
                return WorktreeMergeChoice.DISCARD;
            }
            String trimmed = input.strip().toLowerCase();
            return switch (trimmed) {
                case "m", "merge" -> WorktreeMergeChoice.MERGE;
                case "k", "keep" -> WorktreeMergeChoice.KEEP;
                case "d", "discard", "" -> WorktreeMergeChoice.DISCARD;
                default -> {
                    writer.printf("Unknown choice '%s', defaulting to discard.%n", trimmed);
                    writer.flush();
                    yield WorktreeMergeChoice.DISCARD;
                }
            };
        }
    }

    private String readLineInterruptibly(BufferedReader r) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        while (!Thread.currentThread().isInterrupted()) {
            if (r.ready()) {
                int ch = r.read();
                if (ch == -1) {
                    return sb.isEmpty() ? null : sb.toString();
                }
                if (ch == '\n') {
                    return sb.toString();
                }
                if (ch != '\r') {
                    sb.append((char) ch);
                }
            } else {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        throw new InterruptedException("Worktree merge prompt interrupted");
    }
}
