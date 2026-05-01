import { useState, useEffect } from 'react';
import { ThumbsUp, ThumbsDown } from 'lucide-react';
import { getReaction, setReaction, type Reaction } from '@utils/messageReactions';

interface MessageReactionProps {
    messageId: string;
}

export function MessageReaction({ messageId }: MessageReactionProps) {
    const [reaction, setReactionState] = useState<Reaction>(null);

    useEffect(() => {
        setReactionState(getReaction(messageId)?.reaction ?? null);
    }, [messageId]);

    const toggle = (r: Reaction) => {
        const next = reaction === r ? null : r;
        setReaction(messageId, next);
        setReactionState(next);
    };

    return (
        <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
            <button
                onClick={() => toggle('up')}
                className={`p-1 rounded transition-colors ${
                    reaction === 'up'
                        ? 'text-emerald-400 bg-emerald-400/10'
                        : 'text-[var(--text-muted)] hover:text-emerald-400 hover:bg-emerald-400/10'
                }`}
                title="Good response"
            >
                <ThumbsUp size={12} />
            </button>
            <button
                onClick={() => toggle('down')}
                className={`p-1 rounded transition-colors ${
                    reaction === 'down'
                        ? 'text-red-400 bg-red-400/10'
                        : 'text-[var(--text-muted)] hover:text-red-400 hover:bg-red-400/10'
                }`}
                title="Bad response"
            >
                <ThumbsDown size={12} />
            </button>
        </div>
    );
}
