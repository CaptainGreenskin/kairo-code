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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Thread-safe notification bridge between background worker completion callbacks and the
 * parent agent's {@link PendingBackgroundTaskStrategy}. The callback thread offers
 * notifications; the strategy thread polls (blocking) until one arrives.
 */
public final class BackgroundTaskNotificationQueue {

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    /**
     * Non-blocking enqueue. Called from the worker's virtual thread when a background
     * task completes.
     */
    public void offer(String notification) {
        queue.offer(notification);
    }

    /**
     * Blocking dequeue with timeout. Called from the continuation strategy thread
     * (bounded-elastic) to park until a notification arrives.
     *
     * @return the notification string, or null on timeout
     */
    @Nullable
    public String poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** Non-blocking peek for testing. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Number of queued notifications. */
    public int size() {
        return queue.size();
    }
}
