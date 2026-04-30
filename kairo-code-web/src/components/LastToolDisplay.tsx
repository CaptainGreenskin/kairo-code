import { Check } from 'lucide-react';

interface LastToolDisplayProps {
    name: string;
    elapsed: number;
}

const formatElapsed = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
};

export function LastToolDisplay({ name, elapsed }: LastToolDisplayProps) {
    return (
        <div className="flex justify-start px-4 py-1">
            <div className="flex items-center gap-1 text-[10px] text-green-400 animate-fade-out">
                <Check size={10} />
                <span>{name} {formatElapsed(elapsed)}</span>
            </div>
        </div>
    );
}
