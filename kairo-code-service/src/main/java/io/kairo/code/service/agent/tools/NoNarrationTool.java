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
package io.kairo.code.service.agent.tools;

import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Cheap-output "stay silent" tool used exclusively by the experts-mode active narrator.
 *
 * <p>The narrator dispatcher prompts the Team Lead with a batched summary of expert progress and
 * asks for either a short paragraph of synthesized commentary or — when the batch is routine
 * in-progress noise — a single call to {@code no_narration} with no arguments. Calling this tool
 * is the Team Lead's signal to suppress the surrounding {@code TEXT_CHUNK} emission, keeping token
 * cost bounded when there is nothing worth surfacing.
 *
 * <p>Registered only on the experts-mode arm of {@link
 * io.kairo.code.service.AgentService#createSession}. Agent and Team modes never see this tool, so
 * its presence cannot leak into the baseline tool surface (M-Experts-Upgrade mode-isolation
 * contract).
 */
@Tool(
        name = "no_narration",
        description =
                "Skip surfacing this batch of expert updates to the user. Call with no arguments "
                        + "when the batched expert progress is routine in-progress noise and not worth "
                        + "paraphrasing — the user is already seeing the raw expert stream.",
        category = ToolCategory.GENERAL,
        sideEffect = ToolSideEffect.READ_ONLY)
public final class NoNarrationTool implements SyncTool {

    /** Tool name exposed to the model — kept in sync with the {@link Tool} annotation. */
    public static final String NAME = "no_narration";

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.just(ToolResult.success(null, "ok"));
    }
}
