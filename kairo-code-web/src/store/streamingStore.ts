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

export const streamingStore = {
  append(sessionId: string, chunk: string) {
    const current = streamingChunks.get(sessionId) ?? ''
    streamingChunks.set(sessionId, current + chunk)
    subscribers.forEach(fn => fn())
  },

  getContent(sessionId: string): string {
    return streamingChunks.get(sessionId) ?? ''
  },

  subscribe(fn: () => void): () => void {
    subscribers.add(fn)
    return () => { subscribers.delete(fn) }
  },

  clear(sessionId: string) {
    streamingChunks.delete(sessionId)
  }
}
