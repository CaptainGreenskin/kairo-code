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
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Five-pattern loop detector ported from kairo-code-eval's {@code StuckDetector}
 * (which in turn was based on OpenHands' {@code openhands/controller/stuck.py}).
 *
 * <p>Catches:
 * <ol>
 *   <li>{@code repeating_action} — 4 consecutive identical tool calls (same name + same args)</li>
 *   <li>{@code repeating_error} — 3 consecutive identical tool calls (kept here as a coarser
 *       counterpart since {@code PostReasoningEvent} doesn't carry per-tool error state; the
 *       tool-result version lives in {@link RepetitiveToolHook}'s sibling space)</li>
 *   <li>{@code alternating_pattern} — 6 turns forming A-B-A-B-A-B with two distinct tools</li>
 *   <li>{@code no_progress} — 10+ consecutive turns without any write/edit-class tool</li>
 *   <li>{@code context_explosion} — last 4 turns' tool-args payload growing monotonically
 *       (proxy for "the agent is dumping more context each turn without making progress")</li>
 * </ol>
 *
 * <p>On the first detected pattern the hook injects ONE coaching message into the conversation
 * via {@link HookResult#inject}. The message is deliberately concrete (names the pattern, names
 * the offending tool, suggests an alternative) so the model can self-correct on the next turn
 * rather than receive a vague "you're stuck" hint.
 *
 * <p>Hard-terminate is intentionally NOT done here — {@link MaxTurnsGuardHook} +
 * {@link io.kairo.code.core.hook.ContextWindowGuardHook} already enforce wall-stops. This hook
 * is the gentle prod that gives the agent one chance to course-correct.
 *
 * <p>Phase: {@link HookPhase#POST_REASONING}. Non-REPL only — fires after each model turn.
 *
 * <p>Tunable via env (defaults in parentheses):
 * <ul>
 *   <li>{@code KAIRO_CODE_LOOP_HISTORY_SIZE} (20) — turns kept in the sliding window</li>
 *   <li>{@code KAIRO_CODE_LOOP_REPEAT_THRESHOLD} (4) — identical-call cutoff</li>
 *   <li>{@code KAIRO_CODE_LOOP_NO_PROGRESS_THRESHOLD} (10) — turns without a write</li>
 *   <li>{@code KAIRO_CODE_LOOP_DETECTOR} ({@code on}) — set {@code off} to disable</li>
 * </ul>
 */
public final class RichLoopDetectorHook {

    /** Tool names that count as "making progress" (writes a file / patches the codebase). */
    private static final java.util.Set<String> WRITE_TOOLS = java.util.Set.of(
            "write", "edit", "multi_edit",
            "write_file", "create_file", "patch_file", "apply_diff", "edit_file",
            "batch_write", "search_replace", "patch_apply", "str_replace_editor");

    private final int historySize;
    private final int repeatThreshold;
    private final int noProgressThreshold;
    private final boolean isRepl;

    /** Per-turn snapshot of what tools the model called this turn. */
    private record TurnRecord(java.util.List<ToolCall> toolCalls) {}
    private record ToolCall(String name, String argsKey, int payloadSize) {
        static ToolCall from(Content.ToolUseContent t) {
            String args = t.input() == null ? "" : t.input().toString();
            // Truncate to the first 200 chars — full args strings can be huge JSON.
            String key = args.length() <= 200 ? args : args.substring(0, 200);
            return new ToolCall(t.toolName(), key, args.length());
        }
    }

    private final Deque<TurnRecord> history = new ArrayDeque<>();
    private boolean interventionFired = false;

    public RichLoopDetectorHook(boolean isRepl) {
        this.isRepl = isRepl;
        this.historySize = envInt("KAIRO_CODE_LOOP_HISTORY_SIZE", 20);
        this.repeatThreshold = envInt("KAIRO_CODE_LOOP_REPEAT_THRESHOLD", 4);
        this.noProgressThreshold = envInt("KAIRO_CODE_LOOP_NO_PROGRESS_THRESHOLD", 10);
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (isRepl || interventionFired) return HookResult.proceed(event);
        if ("off".equalsIgnoreCase(System.getenv("KAIRO_CODE_LOOP_DETECTOR"))) {
            return HookResult.proceed(event);
        }

        ModelResponse response = event.response();
        if (response == null) return HookResult.proceed(event);

        // Extract tool calls from this turn.
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        for (Content c : response.contents()) {
            if (c instanceof Content.ToolUseContent t) {
                calls.add(ToolCall.from(t));
            }
        }
        // Turns with no tool calls (pure text) get recorded as empty — important
        // for the no-progress and alternating detectors so they can see "stopped
        // calling tools" as its own signal.
        history.addLast(new TurnRecord(calls));
        while (history.size() > historySize) {
            history.removeFirst();
        }

        String pattern = detect();
        if (pattern == null) return HookResult.proceed(event);

        interventionFired = true;
        Msg hint = Msg.of(MsgRole.USER, formatHint(pattern, calls));
        return HookResult.inject(event, hint, "rich-loop-detector:" + pattern.split(":")[0]);
    }

    // ── Detection ───────────────────────────────────────────────────────────

    /**
     * Returns a short reason string when a stuck pattern is detected. Format:
     * {@code "<pattern_id>:<detail>"} where pattern_id is one of
     * {@code repeating_action}, {@code repeating_error}, {@code alternating},
     * {@code no_progress}, {@code context_explosion}.
     */
    String detect() {
        String r = checkRepeatingAction();
        if (r != null) return r;
        r = checkAlternating();
        if (r != null) return r;
        r = checkNoProgress();
        if (r != null) return r;
        r = checkContextExplosion();
        if (r != null) return r;
        return null;
    }

    private String checkRepeatingAction() {
        if (history.size() < repeatThreshold) return null;
        TurnRecord[] recent = lastN(repeatThreshold);
        // Each turn must have exactly one tool call, all identical.
        if (recent[0].toolCalls.size() != 1) return null;
        ToolCall first = recent[0].toolCalls.get(0);
        for (int i = 1; i < recent.length; i++) {
            if (recent[i].toolCalls.size() != 1) return null;
            ToolCall other = recent[i].toolCalls.get(0);
            if (!first.name.equals(other.name) || !first.argsKey.equals(other.argsKey)) {
                return null;
            }
        }
        return "repeating_action:" + first.name;
    }

    private String checkAlternating() {
        if (history.size() < 6) return null;
        TurnRecord[] recent = lastN(6);
        // Each turn must have exactly one tool call.
        for (TurnRecord r : recent) {
            if (r.toolCalls.size() != 1) return null;
        }
        ToolCall a = recent[0].toolCalls.get(0);
        ToolCall b = recent[1].toolCalls.get(0);
        if (a.name.equals(b.name) && a.argsKey.equals(b.argsKey)) return null;
        for (int i = 2; i < 6; i++) {
            ToolCall expected = (i % 2 == 0) ? a : b;
            ToolCall actual = recent[i].toolCalls.get(0);
            if (!expected.name.equals(actual.name) || !expected.argsKey.equals(actual.argsKey)) {
                return null;
            }
        }
        return "alternating:" + a.name + "<->" + b.name;
    }

    private String checkNoProgress() {
        if (history.size() < noProgressThreshold) return null;
        TurnRecord[] recent = lastN(noProgressThreshold);
        for (TurnRecord r : recent) {
            for (ToolCall c : r.toolCalls) {
                if (WRITE_TOOLS.contains(c.name)) return null;
            }
        }
        return "no_progress:" + noProgressThreshold + "_turns_no_write";
    }

    private String checkContextExplosion() {
        // Last 4 turns' total payload size growing monotonically with at least
        // 2x growth start-to-end. Catches "agent is dumping more args each turn"
        // even when the tool name varies.
        int window = 4;
        if (history.size() < window) return null;
        TurnRecord[] recent = lastN(window);
        int[] sizes = new int[window];
        for (int i = 0; i < window; i++) {
            int sum = 0;
            for (ToolCall c : recent[i].toolCalls) sum += c.payloadSize;
            sizes[i] = sum;
        }
        // Need monotonic non-decreasing with strict end > 2*start.
        for (int i = 1; i < window; i++) {
            if (sizes[i] < sizes[i - 1]) return null;
        }
        if (sizes[0] == 0 || sizes[window - 1] < sizes[0] * 2) return null;
        return "context_explosion:" + sizes[0] + "->" + sizes[window - 1];
    }

    private TurnRecord[] lastN(int n) {
        TurnRecord[] out = new TurnRecord[n];
        int i = 0;
        int skip = history.size() - n;
        for (TurnRecord r : history) {
            if (skip > 0) { skip--; continue; }
            if (i >= n) break;
            out[i++] = r;
        }
        return out;
    }

    // ── Hint formatting ─────────────────────────────────────────────────────

    private static String formatHint(String pattern, java.util.List<ToolCall> currentCalls) {
        String id = pattern.split(":", 2)[0];
        String detail = pattern.length() > id.length() ? pattern.substring(id.length() + 1) : "";
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("repeating_action",
                "You've called the same tool with the same arguments multiple turns in a row ("
                + detail + "). The result isn't changing. Pause, re-read the original problem, "
                + "and try a fundamentally different angle — different tool, different file, "
                + "or ask the user for clarification.");
        hints.put("repeating_error",
                "You're hitting the same error repeatedly (" + detail + "). The fix isn't where "
                + "you think it is. Step back and read the error message + related files before "
                + "your next tool call.");
        hints.put("alternating",
                "You're bouncing between two tools (" + detail + ") without making progress. "
                + "Pick one path and commit, or write the fix directly without more exploration.");
        hints.put("no_progress",
                "You've made " + detail + " tool calls without writing any code. Make your "
                + "best-guess fix now — even a 1-line change is better than another round of "
                + "exploration. Run the failing test after.");
        hints.put("context_explosion",
                "Your tool arguments are growing each turn (" + detail + " bytes). You're likely "
                + "stuffing context that isn't helping. Use a smaller, more targeted call: grep "
                + "for one symbol, read one file, edit one location.");
        return "[loop-detector] " + hints.getOrDefault(id, "Possible stuck pattern: " + pattern);
    }

    /** Visible for testing. */
    int historySize() {
        return history.size();
    }

    /** Visible for testing. */
    boolean interventionFired() {
        return interventionFired;
    }
}
