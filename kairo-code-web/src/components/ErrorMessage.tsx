import { AlertCircle, Wifi, Clock, BookOpen, KeyRound } from 'lucide-react';

export type ErrorType = 'rate_limit' | 'context_length' | 'network' | 'auth' | 'unknown';

interface ErrorMessageProps {
    message: string;
    errorType?: ErrorType;
    onRetry?: () => void;
}

const ERROR_CONFIG: Record<ErrorType, { icon: React.ElementType; title: string; hint: string; color: string }> = {
    rate_limit: {
        icon: Clock,
        title: 'Rate limit reached',
        hint: 'Too many requests. Please wait a moment and try again.',
        color: 'text-amber-400 border-amber-500/30 bg-amber-500/5',
    },
    context_length: {
        icon: BookOpen,
        title: 'Context too long',
        hint: 'The conversation is too long for the model. Start a new session or clear some messages.',
        color: 'text-purple-400 border-purple-500/30 bg-purple-500/5',
    },
    network: {
        icon: Wifi,
        title: 'Network error',
        hint: 'Check your connection. The agent will retry automatically.',
        color: 'text-blue-400 border-blue-500/30 bg-blue-500/5',
    },
    auth: {
        icon: KeyRound,
        title: 'Authentication failed',
        hint: 'The API key looks invalid or unauthorized. Open Settings to update credentials, then click Retry.',
        color: 'text-orange-400 border-orange-500/30 bg-orange-500/5',
    },
    unknown: {
        icon: AlertCircle,
        title: 'Error',
        hint: '',
        color: 'text-red-400 border-red-500/30 bg-red-500/5',
    },
};

export function ErrorMessage({ message, errorType = 'unknown', onRetry }: ErrorMessageProps) {
    const config = ERROR_CONFIG[errorType];
    const Icon = config.icon;
    // For categorized errors, the hint is the user-facing summary and the raw
    // message is supplementary. For 'unknown' there's no useful hint, so promote
    // the actual message to the body — hiding it behind a disclosure (the old
    // behavior) made misconfigured-key sessions look like generic "An unexpected
    // error occurred" with no actionable info.
    const showMessageInline = errorType === 'unknown' && !!message;
    return (
        <div className={`my-2 mx-4 p-3 rounded-lg border text-sm ${config.color}`}>
            <div className="flex items-start gap-2">
                <Icon size={15} className="mt-0.5 flex-shrink-0" />
                <div className="flex-1 min-w-0">
                    <div className="font-medium">{config.title}</div>
                    {showMessageInline ? (
                        <div className="mt-0.5 text-xs opacity-90 break-words whitespace-pre-wrap">
                            {message}
                        </div>
                    ) : (
                        <>
                            {config.hint && (
                                <div className="mt-0.5 text-xs opacity-80">{config.hint}</div>
                            )}
                            {message && (
                                <details className="mt-1">
                                    <summary className="text-[10px] cursor-pointer opacity-60 hover:opacity-100">
                                        Technical details
                                    </summary>
                                    <pre className="mt-1 text-[10px] font-mono whitespace-pre-wrap break-all opacity-70">
                                        {message}
                                    </pre>
                                </details>
                            )}
                        </>
                    )}
                </div>
                {onRetry && (
                    <button
                        onClick={onRetry}
                        className="ml-2 text-xs px-2 py-1 rounded border border-current opacity-70 hover:opacity-100 transition-opacity flex-shrink-0"
                    >
                        Retry
                    </button>
                )}
            </div>
        </div>
    );
}
