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
package io.kairo.code.core.task;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.core.agent.continuation.AgentContinuationStrategy;
import io.kairo.core.agent.continuation.ContinuationContext;
import io.kairo.core.agent.continuation.ContinuationDecision;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Prevents the parent agent from terminating while background subagents are still running.
 *
 * <p>When the model emits a text-only response (no tool calls) and active background tasks
 * exist in the {@link SubagentRegistry}, this strategy <b>blocks</b> on the
 * {@link BackgroundTaskNotificationQueue} until a task completion notification arrives.
 * The notification is then injected as a synthetic user message via {@link ContinuationDecision.Nudge},
 * causing the ReAct loop to continue and the model to process the result.
 *
 * <p>This avoids both premature termination (agent returns before workers finish) and
 * wasteful polling (no extra model calls while waiting).
 */
public final class PendingBackgroundTaskStrategy implements AgentContinuationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(PendingBackgroundTaskStrategy.class);

    private static final long DEFAULT_POLL_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_MAX_NUDGES = 50;

    private final SubagentRegistry registry;
    private final BackgroundTaskNotificationQueue queue;
    private final long pollTimeoutSeconds;
    private final int maxNudges;
    private final AtomicInteger nudgeCount = new AtomicInteger(0);

    public PendingBackgroundTaskStrategy(
            SubagentRegistry registry, BackgroundTaskNotificationQueue queue) {
        this(registry, queue, DEFAULT_POLL_TIMEOUT_SECONDS, DEFAULT_MAX_NUDGES);
    }

    public PendingBackgroundTaskStrategy(
            SubagentRegistry registry,
            BackgroundTaskNotificationQueue queue,
            long pollTimeoutSeconds,
            int maxNudges) {
        this.registry = registry;
        this.queue = queue;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.maxNudges = maxNudges;
    }

    @Override
    public Mono<ContinuationDecision> decide(ContinuationContext ctx) {
        List<String> active = registry.activeNames();
        if (active.isEmpty()) {
            return Mono.just(ContinuationDecision.Pass.INSTANCE);
        }

        if (nudgeCount.get() >= maxNudges) {
            LOG.warn("PendingBackgroundTaskStrategy: max nudges ({}) reached, terminating", maxNudges);
            return Mono.just(new ContinuationDecision.Terminate("background_task_max_nudges"));
        }

        LOG.info("PendingBackgroundTaskStrategy: {} active background tasks ({}), "
                + "parking agent thread until notification arrives",
                active.size(), active);

        return Mono.fromCallable(() -> {
            String notification = queue.poll(pollTimeoutSeconds, TimeUnit.SECONDS);
            if (notification == null) {
                LOG.warn("PendingBackgroundTaskStrategy: poll timed out after {}s, "
                        + "remaining active tasks: {}", pollTimeoutSeconds, registry.activeNames());
                return (ContinuationDecision) new ContinuationDecision.Terminate(
                        "background_task_timeout");
            }
            nudgeCount.incrementAndGet();
            LOG.info("PendingBackgroundTaskStrategy: notification received (nudge #{}), "
                    + "waking agent", nudgeCount.get());
            Msg syntheticMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .addContent(new io.kairo.api.message.Content.TextContent(notification))
                    .metadata("kairo.kind", "task_notification")
                    .metadata("kairo.internal", "true")
                    .build();
            return (ContinuationDecision) new ContinuationDecision.Nudge(
                    syntheticMsg, "background_task_completed");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String name() {
        return "PendingBackgroundTask";
    }

    /** Visible for testing. */
    int nudgeCount() {
        return nudgeCount.get();
    }
}
