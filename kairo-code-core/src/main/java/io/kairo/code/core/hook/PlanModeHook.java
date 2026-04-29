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
package io.kairo.code.core.hook;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Plan mode: intercepts the first tool call, displays the agent's reasoning as an execution plan,
 * and requires user confirmation before proceeding.
 *
 * <p>Two phases:
 * <ul>
 *   <li>{@link HookPhase#POST_REASONING} — caches the reasoning text for later display</li>
 *   <li>{@link HookPhase#PRE_ACTING} — on iteration 1, shows the plan and waits for y/N</li>
 * </ul>
 *
 * <p>Subsequent tool calls (iteration &gt; 1) are passed through without prompting.
 *
 * <p>When the user rejects the plan, returns {@link HookResult#abort} so the agent stops.
 * The caller can check {@link #wasRejected()} to determine the appropriate exit code.
 */
public final class PlanModeHook {

    private static final String PLAN_HEADER =
            "\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510\n"
            + "\u2502  kairo-code PLAN (confirm to execute)   \u2502\n"
            + "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518";

    private final boolean enabled;
    private final InputStream stdin;

    /** Whether the user rejected the plan (abort was returned). */
    private boolean rejected;

    /** Cached reasoning text from the most recent POST_REASONING. */
    private String lastReasoningText;

    /** Tracks whether the plan prompt has already been shown. */
    private boolean planShown;

    public PlanModeHook(boolean enabled) {
        this(enabled, System.in);
    }

    /** Package-private constructor that accepts a custom stdin for testing. */
    PlanModeHook(boolean enabled, InputStream stdin) {
        this.enabled = enabled;
        this.stdin = stdin;
        this.rejected = false;
        this.planShown = false;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (!enabled) return HookResult.proceed(event);
        ModelResponse response = event.response();
        if (response != null && response.contents() != null) {
            this.lastReasoningText = response.contents().stream()
                    .filter(Content.TextContent.class::isInstance)
                    .map(c -> ((Content.TextContent) c).text())
                    .findFirst()
                    .orElse(null);
        }
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.PRE_ACTING)
    public HookResult<PreActingEvent> onPreActing(PreActingEvent event) {
        if (!enabled) return HookResult.proceed(event);
        // Only intercept the first tool call — plan already confirmed after that.
        if (planShown) return HookResult.proceed(event);
        planShown = true;

        String planText = lastReasoningText != null ? lastReasoningText : "(no plan text available)";
        System.out.println(PLAN_HEADER);
        System.out.println(planText);
        System.out.println();
        System.out.print("Proceed? [y/N] ");
        System.out.flush();

        String answer = readLine();
        if ("y".equalsIgnoreCase(answer)) {
            return HookResult.proceed(event);
        }

        rejected = true;
        System.err.println();
        return HookResult.abort(event, "Plan rejected by user. No changes made.");
    }

    /** Returns true if the user rejected the plan. */
    public boolean wasRejected() {
        return rejected;
    }

    private String readLine() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stdin, StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (IOException e) {
            return "";
        }
    }
}
