import { useEffect } from 'react';
import { X, CheckCircle, AlertTriangle, Info } from 'lucide-react';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

export interface ToastMessage {
    id: string;
    type: ToastType;
    message: string;
    duration?: number;   // ms, default 4000; 0 = persistent
}

interface ToastProps {
    toasts: ToastMessage[];
    onDismiss: (id: string) => void;
}

const icons = {
    success: <CheckCircle size={16} className="text-green-500 shrink-0" />,
    error: <AlertTriangle size={16} className="text-red-500 shrink-0" />,
    warning: <AlertTriangle size={16} className="text-amber-500 shrink-0" />,
    info: <Info size={16} className="text-blue-500 shrink-0" />,
};

function ToastItem({ toast, onDismiss }: { toast: ToastMessage; onDismiss: (id: string) => void }) {
    useEffect(() => {
        const duration = toast.duration ?? 4000;
        if (duration === 0) return;
        const timer = setTimeout(() => onDismiss(toast.id), duration);
        return () => clearTimeout(timer);
    }, [toast.id, toast.duration, onDismiss]);

    return (
        <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg shadow-lg bg-[var(--bg-primary)] border border-[var(--border)] text-sm max-w-sm animate-fade-in">
            {icons[toast.type]}
            <span className="flex-1 text-[var(--text-primary)]">{toast.message}</span>
            <button onClick={() => onDismiss(toast.id)} className="text-[var(--text-muted)] hover:text-[var(--text-secondary)] shrink-0">
                <X size={14} />
            </button>
        </div>
    );
}

export function ToastContainer({ toasts, onDismiss }: ToastProps) {
    if (toasts.length === 0) return null;
    return (
        <div className="fixed bottom-4 right-4 flex flex-col gap-2 z-50">
            {toasts.map(t => (
                <ToastItem key={t.id} toast={t} onDismiss={onDismiss} />
            ))}
        </div>
    );
}
