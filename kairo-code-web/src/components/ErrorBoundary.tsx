import { Component, type ReactNode, type ErrorInfo } from 'react';

interface Props {
    children: ReactNode;
    fallback?: ReactNode;
}

interface State {
    hasError: boolean;
    error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
    state: State = { hasError: false, error: null };

    static getDerivedStateFromError(error: Error): State {
        return { hasError: true, error };
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('[ErrorBoundary]', error, info.componentStack);
    }

    render() {
        if (this.state.hasError) {
            if (this.props.fallback) return this.props.fallback;
            return (
                <div className="flex flex-col items-center justify-center h-full gap-4 text-[var(--text-secondary)]">
                    <div className="text-4xl">⚠️</div>
                    <div className="text-sm font-medium">Something went wrong</div>
                    <div className="text-xs text-[var(--text-muted)] max-w-xs text-center">
                        {this.state.error?.message ?? 'Unknown error'}
                    </div>
                    <button
                        onClick={() => this.setState({ hasError: false, error: null })}
                        className="px-3 py-1.5 text-xs rounded bg-[var(--accent)] text-white hover:opacity-90"
                    >
                        Try again
                    </button>
                </div>
            );
        }
        return this.props.children;
    }
}
