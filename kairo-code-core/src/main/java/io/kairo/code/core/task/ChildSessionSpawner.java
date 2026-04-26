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

import io.kairo.code.core.CodeAgentSession;
import java.nio.file.Path;

/**
 * Builds a child {@link CodeAgentSession} for a sub-task. Implementations decide how the child
 * inherits parent state (model provider, hooks, approval handler, skills) and which working
 * directory the child sees.
 *
 * <p>Implementations <strong>must not</strong> register the {@code task} tool itself in the child
 * session — recursion is out of scope for M3.
 */
@FunctionalInterface
public interface ChildSessionSpawner {

    /**
     * @param taskId stable id for this sub-task (used for logging + event prefixing)
     * @param workingDir the directory the child should treat as cwd (worktree root or parent root)
     */
    CodeAgentSession spawn(String taskId, Path workingDir);
}
