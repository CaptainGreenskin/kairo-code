import { useState, useEffect, useCallback } from 'react';
import { useExpertTeamStore, type TeamWsEvent } from '../store/expertTeamStore';
import { ExpertTeamPanel } from './ExpertTeamPanel';

type ReplaySpeed = 1 | 2 | 4 | 'instant';

interface TeamReplayProps {
  teamId: string;
  onClose: () => void;
}

export function TeamReplay({ teamId, onClose }: TeamReplayProps) {
  const [events, setEvents] = useState<TeamWsEvent[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [speed, setSpeed] = useState<ReplaySpeed>(1);
  const [isPlaying, setIsPlaying] = useState(false);
  const [loading, setLoading] = useState(true);
  const store = useExpertTeamStore();

  // Load events from API on mount
  useEffect(() => {
    setLoading(true);
    fetch(`/api/teams/${teamId}/events`)
      .then(res => res.json())
      .then((data: TeamWsEvent[]) => {
        setEvents(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [teamId]);

  // Replay logic
  useEffect(() => {
    if (!isPlaying || currentIndex >= events.length) {
      if (currentIndex >= events.length && isPlaying) {
        setIsPlaying(false);
      }
      return;
    }

    const event = events[currentIndex];
    const nextEvent = events[currentIndex + 1];

    // Dispatch event to store
    store.handleTeamEvent(event);

    if (speed === 'instant') {
      // Instant: dispatch all remaining immediately
      for (let i = currentIndex + 1; i < events.length; i++) {
        store.handleTeamEvent(events[i]);
      }
      setCurrentIndex(events.length - 1);
      setIsPlaying(false);
      return;
    }

    if (!nextEvent) {
      setIsPlaying(false);
      return;
    }

    // Calculate delay from timestamps
    const currentTime = new Date(event.timestamp).getTime();
    const nextTime = new Date(nextEvent.timestamp).getTime();
    const delay = Math.max(10, (nextTime - currentTime) / speed);

    const timer = setTimeout(() => {
      setCurrentIndex(i => i + 1);
    }, Math.min(delay, 2000)); // cap at 2s max between events

    return () => clearTimeout(timer);
  }, [isPlaying, currentIndex, speed, events, store]);

  // Reset store state and start replay from beginning
  const startReplay = useCallback(() => {
    store.reset();
    setCurrentIndex(0);
    setIsPlaying(true);
  }, [store]);

  // Jump to specific position
  const jumpTo = useCallback((index: number) => {
    store.reset();
    // Replay all events up to index instantly
    for (let i = 0; i <= index; i++) {
      store.handleTeamEvent(events[i]);
    }
    setCurrentIndex(index);
    setIsPlaying(false);
  }, [store, events]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full bg-[var(--bg-primary)]">
        <p className="text-xs text-[var(--text-muted)]">Loading replay events...</p>
      </div>
    );
  }

  if (events.length === 0) {
    return (
      <div className="flex flex-col h-full bg-[var(--bg-primary)]">
        <div className="flex items-center gap-2 px-4 py-3 border-b border-[var(--border)]">
          <button
            onClick={onClose}
            className="text-xs text-[var(--text-secondary)] hover:text-[var(--text-primary)]
                       transition-colors"
          >
            ← Back
          </button>
        </div>
        <div className="flex items-center justify-center flex-1">
          <p className="text-xs text-[var(--text-muted)]">No events found for this team.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-[var(--bg-primary)]">
      {/* Controls bar */}
      <div className="flex items-center gap-3 px-4 py-2 border-b border-[var(--border)]
                      bg-[var(--bg-secondary)]">
        <button
          onClick={onClose}
          className="text-xs text-[var(--text-secondary)] hover:text-[var(--text-primary)]
                     transition-colors font-medium"
        >
          ← Back
        </button>

        <div className="w-px h-4 bg-[var(--border)]" />

        <button
          onClick={startReplay}
          className="px-2 py-1 text-[11px] font-medium rounded border border-[var(--border)]
                     text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] transition-colors"
        >
          ⏮ Restart
        </button>

        <button
          onClick={() => setIsPlaying(!isPlaying)}
          className="px-2 py-1 text-[11px] font-medium rounded border border-[var(--border)]
                     text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] transition-colors"
        >
          {isPlaying ? '⏸ Pause' : '▶ Play'}
        </button>

        {/* Speed selector */}
        <select
          value={speed}
          onChange={e => {
            const val = e.target.value;
            setSpeed(val === 'instant' ? 'instant' : (Number(val) as 1 | 2 | 4));
          }}
          className="px-2 py-1 text-[11px] rounded border border-[var(--border)]
                     bg-[var(--bg-primary)] text-[var(--text-secondary)]"
        >
          <option value={1}>1x</option>
          <option value={2}>2x</option>
          <option value={4}>4x</option>
          <option value="instant">Instant</option>
        </select>

        {/* Progress slider */}
        <input
          type="range"
          min={0}
          max={events.length - 1}
          value={currentIndex}
          onChange={e => jumpTo(Number(e.target.value))}
          className="flex-1 h-1.5 accent-[var(--accent)]"
        />

        <span className="text-[10px] text-[var(--text-muted)] whitespace-nowrap">
          {currentIndex + 1}/{events.length} events
        </span>
      </div>

      {/* Reuse ExpertTeamPanel in read-only mode */}
      <div className="flex-1 overflow-hidden">
        <ExpertTeamPanel teamId={teamId} readOnly />
      </div>
    </div>
  );
}
