import { useCallback, useEffect, useRef, useState } from 'react';
import { useExpertTeamStore, type TeamWsEvent } from '../store/expertTeamStore';

/** Event types that are high-frequency and should be batched via rAF. */
const BUFFERED_EVENT_TYPES = new Set(['STEP_THINKING', 'STEP_ARTIFACT_CHUNK']);

/**
 * Options for the expert team handler hook.
 *
 * Integration: the parent component that owns useAgentWebSocket should:
 * 1. Pass its generic `send` function as `sendAction`
 * 2. Pass `isConnected` from the WS hook's connectionStatus
 * 3. Forward raw WS messages via the returned `handleRawMessage`
 */
export interface UseExpertTeamHandlerOptions {
  /** Generic function to send a JSON payload over the shared WebSocket. */
  sendAction: (payload: Record<string, unknown>) => boolean;
  /** Whether the shared WebSocket is currently connected. */
  isConnected: boolean;
}

export interface UseExpertTeamHandlerReturn {
  /** Whether currently subscribed to a team's events. */
  isSubscribed: boolean;
  /** Last received sequence number (used for resume on reconnect). */
  lastSeq: number;
  /** Subscribe to a team's live events. */
  subscribe: (teamId: string) => void;
  /** Unsubscribe from the current team's events. */
  unsubscribe: () => void;
  /**
   * Feed raw WebSocket message data to this handler.
   * It will filter for TEAM_EVENT messages and dispatch to the store.
   * Call this from the WebSocket onmessage handler.
   */
  handleRawMessage: (data: string) => void;
}

/**
 * Hook that subscribes to expert-team events over the shared WebSocket
 * and dispatches them to the expertTeamStore.
 *
 * Wire protocol:
 *   client → server: {"action":"subscribeTeam","teamId":"...","lastSeq":N}
 *   client → server: {"action":"unsubscribeTeam","teamId":"..."}
 *   server → client: {"type":"TEAM_EVENT","teamId":"...","eventType":"...","seq":N,...}
 */
export function useExpertTeamHandler(
  options: UseExpertTeamHandlerOptions,
): UseExpertTeamHandlerReturn {
  const { sendAction, isConnected } = options;

  const [isSubscribed, setIsSubscribed] = useState(false);
  const lastSeqRef = useRef<number>(0);
  const subscribedTeamRef = useRef<string | null>(null);
  const prevConnectedRef = useRef<boolean>(isConnected);

  const handleTeamEvent = useExpertTeamStore((state) => state.handleTeamEvent);
  const handleBatchEvents = useExpertTeamStore((state) => state.handleBatchEvents);

  // ── rAF batching for high-frequency events ─────────────────────────────

  const eventBufferRef = useRef<TeamWsEvent[]>([]);
  const rafIdRef = useRef<number | null>(null);

  const scheduleFlush = useCallback(() => {
    if (rafIdRef.current === null) {
      rafIdRef.current = requestAnimationFrame(() => {
        rafIdRef.current = null;
        const buffer = eventBufferRef.current;
        if (buffer.length > 0) {
          eventBufferRef.current = [];
          handleBatchEvents(buffer);
        }
      });
    }
  }, [handleBatchEvents]);

  // ── Subscribe to a team ──────────────────────────────────────────────────

  const subscribe = useCallback(
    (teamId: string) => {
      // If switching teams, unsubscribe from the previous one first
      if (subscribedTeamRef.current && subscribedTeamRef.current !== teamId) {
        sendAction({
          action: 'unsubscribeTeam',
          teamId: subscribedTeamRef.current,
        });
        // Reset seq when switching teams
        lastSeqRef.current = 0;
      }

      subscribedTeamRef.current = teamId;
      setIsSubscribed(true);

      sendAction({
        action: 'subscribeTeam',
        teamId,
        lastSeq: lastSeqRef.current,
      });
    },
    [sendAction],
  );

  // ── Unsubscribe ──────────────────────────────────────────────────────────

  const unsubscribe = useCallback(() => {
    if (subscribedTeamRef.current) {
      sendAction({
        action: 'unsubscribeTeam',
        teamId: subscribedTeamRef.current,
      });
      subscribedTeamRef.current = null;
      setIsSubscribed(false);
    }
  }, [sendAction]);

  // ── Message handler (filters for TEAM_EVENT) ─────────────────────────────

  const handleRawMessage = useCallback(
    (data: string) => {
      try {
        const parsed = JSON.parse(data);
        if (parsed.type !== 'TEAM_EVENT') return;

        const teamEvent: TeamWsEvent = parsed;

        // Only process events for the subscribed team
        if (teamEvent.teamId !== subscribedTeamRef.current) return;

        // Dedup: only forward events with seq > last seen
        if (teamEvent.seq > lastSeqRef.current) {
          lastSeqRef.current = teamEvent.seq;

          // High-frequency events: buffer and flush via rAF
          if (BUFFERED_EVENT_TYPES.has(teamEvent.eventType)) {
            eventBufferRef.current.push(teamEvent);
            scheduleFlush();
          } else {
            // Lifecycle events: dispatch immediately
            handleTeamEvent(teamEvent);
          }
        }
      } catch {
        // Ignore non-JSON or malformed messages
      }
    },
    [handleTeamEvent, scheduleFlush],
  );

  // ── Reconnection: re-subscribe with lastSeq for replay ───────────────────

  useEffect(() => {
    const wasConnected = prevConnectedRef.current;
    prevConnectedRef.current = isConnected;

    // Detect reconnection: was disconnected, now connected
    if (!wasConnected && isConnected && subscribedTeamRef.current) {
      // Re-subscribe with lastSeq so the server replays missed events
      sendAction({
        action: 'subscribeTeam',
        teamId: subscribedTeamRef.current,
        lastSeq: lastSeqRef.current,
      });
    }
  }, [isConnected, sendAction]);

  // ── Cleanup on unmount ───────────────────────────────────────────────────

  useEffect(() => {
    return () => {
      // Cancel pending rAF
      if (rafIdRef.current !== null) {
        cancelAnimationFrame(rafIdRef.current);
        rafIdRef.current = null;
      }
      // Flush any remaining buffered events synchronously
      if (eventBufferRef.current.length > 0) {
        handleBatchEvents(eventBufferRef.current);
        eventBufferRef.current = [];
      }
      if (subscribedTeamRef.current) {
        sendAction({
          action: 'unsubscribeTeam',
          teamId: subscribedTeamRef.current,
        });
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    isSubscribed,
    lastSeq: lastSeqRef.current,
    subscribe,
    unsubscribe,
    handleRawMessage,
  };
}
