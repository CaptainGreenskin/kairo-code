/**
 * External streaming store — bypasses Zustand/Immer for high-frequency TEXT_CHUNK updates.
 *
 * TEXT_CHUNK events arrive dozens of times per second. Using Zustand's immutable update pattern
 * (via Immer) causes UI stutter because every chunk triggers a full state copy.
 *
 * This store uses direct mutation + subscribers, similar to zhikuncode's approach.
 * On AGENT_DONE, the accumulated content is flushed to sessionStore and cleared here.
 */

const streamingChunks: Map<string, string> = new Map()
const subscribers: Set<() => void> = new Set()

/**
 * Collapse a string that is entirely N copies of a repeating prefix down to
 * a single copy. Backend retries (ReactiveRetryPolicy) re-stream the same
 * model response on transient failures and the chunks get appended downstream
 * before the client knows a retry happened, producing visible "X X X X" output.
 *
 * Two thresholds — short units need more copies to qualify as duplication so
 * legitimate stutter ("嗯嗯嗯", "okok") doesn't get collapsed:
 *   - unit ≥15 chars and ≥2 copies (a paragraph repeated twice)
 *   - unit ≥4 chars and ≥3 copies (Chinese phrases like "你好！有什么可以帮你的吗？"
 *     that are too short for the 15-char floor — typical retry duplication
 *     repeats the SAME phrase many times, well beyond 3)
 *
 * In both cases the repetition must cover effectively the entire buffer
 * (`unit.startsWith(remaining)`), so a single repeated phrase inside an
 * otherwise unique paragraph won't trip this.
 */
export function collapseRetryRepeats(text: string): string {
  const len = text.length
  if (len < 12) return text
  const maxUnit = Math.floor(len / 2)
  for (let unitLen = maxUnit; unitLen >= 4; unitLen--) {
    const unit = text.slice(0, unitLen)
    if (text.slice(unitLen, unitLen * 2) !== unit) continue
    let copies = 1
    let pos = unitLen
    while (text.slice(pos, pos + unitLen) === unit) {
      copies++
      pos += unitLen
    }
    const remaining = text.slice(pos)
    if (!unit.startsWith(remaining)) continue
    if (unitLen >= 15 && copies >= 2) return unit
    if (copies >= 3) return unit
  }
  return text
}

export const streamingStore = {
  /**
   * Append a text chunk to the buffer for the given session.
   * @param notify — when false, accumulates silently without triggering subscriber
   *   re-renders. Used for background sessions so their events don't cause the
   *   active session's UI to re-render needlessly.
   */
  append(sessionId: string, chunk: string, notify = true) {
    const current = streamingChunks.get(sessionId) ?? ''
    streamingChunks.set(sessionId, current + chunk)
    if (notify) subscribers.forEach(fn => fn())
  },

  getContent(sessionId: string): string {
    return streamingChunks.get(sessionId) ?? ''
  },

  /**
   * Read the accumulated content with backend-retry duplicates collapsed.
   * Used by AGENT_DONE / tool-call-boundary handlers when finalizing a bubble.
   */
  getContentDeduped(sessionId: string): string {
    return collapseRetryRepeats(streamingChunks.get(sessionId) ?? '')
  },

  subscribe(fn: () => void): () => void {
    subscribers.add(fn)
    return () => { subscribers.delete(fn) }
  },

  clear(sessionId: string) {
    streamingChunks.delete(sessionId)
  }
}
