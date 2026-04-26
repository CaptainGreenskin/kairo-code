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

import io.kairo.api.tool.UserApprovalHandler;
import io.kairo.code.cli.AgentEventPrinter;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentFactory.SessionOptions;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.task.ChildSessionSpawner;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * REPL-side {@link ChildSessionSpawner} that builds child sessions through {@link
 * CodeAgentFactory} with {@link SessionOptions#asChildSession()}.
 *
 * <p>Each child gets its own copy of the parent's {@link CodeAgentConfig} but with {@code
 * workingDir} overridden to the worktree path supplied by the {@code task} tool. The child shares
 * the parent's approval handler (so users only get one approval flow) and gets a prefixed {@link
 * AgentEventPrinter} so streaming output is visually distinct from the parent's.
 *
 * <p>Children are explicitly NOT given task tool dependencies — recursion is out of scope for M3
 * and the factory enforces this via the {@code childSession} flag.
 */
public final class ReplChildSessionSpawner implements ChildSessionSpawner {

    private final CodeAgentConfig parentConfig;
    private final UserApprovalHandler approvalHandler;
    private final PrintWriter writer;

    public ReplChildSessionSpawner(
            CodeAgentConfig parentConfig,
            UserApprovalHandler approvalHandler,
            PrintWriter writer) {
        this.parentConfig = parentConfig;
        this.approvalHandler = approvalHandler;
        this.writer = writer;
    }

    @Override
    public CodeAgentSession spawn(String taskId, Path workingDir) {
        CodeAgentConfig childConfig =
                new CodeAgentConfig(
                        parentConfig.apiKey(),
                        parentConfig.baseUrl(),
                        parentConfig.modelName(),
                        parentConfig.maxIterations(),
                        workingDir.toString());

        AgentEventPrinter prefixedPrinter = new AgentEventPrinter(writer, "[task:" + taskId + "] ");

        SessionOptions opts =
                SessionOptions.empty()
                        .withApprovalHandler(approvalHandler)
                        .withHooks(List.of(prefixedPrinter))
                        .asChildSession();

        return CodeAgentFactory.createSession(childConfig, opts);
    }
}
