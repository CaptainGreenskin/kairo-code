package io.kairo.code.core.session;

import java.time.Instant;

/**
 * A single turn in a JSONL session log.
 *
 * @param role  the message role ("user" or "assistant")
 * @param content  the message content
 * @param tokens  token count (0 for user turns, model-reported for assistant turns)
 * @param ts  timestamp of when the turn was recorded
 */
public record SessionTurn(String role, String content, int tokens, Instant ts) {}
