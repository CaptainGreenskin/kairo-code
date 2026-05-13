import { useState } from 'react';

export interface RejectFeedbackModalProps {
  roleName: string;
  stepId: string;
  visible: boolean;
  onSubmit: (feedback: string) => void;
  onCancel: () => void;
}

export function RejectFeedbackModal({
  roleName,
  stepId,
  visible,
  onSubmit,
  onCancel,
}: RejectFeedbackModalProps) {
  const [feedback, setFeedback] = useState('');

  if (!visible) return null;

  const handleSubmit = () => {
    if (feedback.trim()) {
      onSubmit(feedback.trim());
      setFeedback('');
    }
  };

  const handleCancel = () => {
    setFeedback('');
    onCancel();
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={handleCancel}
      />

      {/* Modal */}
      <div className="relative bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl
                      shadow-2xl w-full max-w-md mx-4 overflow-hidden">
        {/* Header */}
        <div className="px-5 py-4 border-b border-[var(--border)]">
          <h3 className="text-sm font-semibold text-[var(--text-primary)]">
            Provide feedback for {roleName}
          </h3>
          <p className="text-[11px] text-[var(--text-muted)] mt-0.5">
            Step: {stepId}
          </p>
        </div>

        {/* Body */}
        <div className="px-5 py-4">
          <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1.5">
            What should be changed or improved?
          </label>
          <textarea
            className="w-full h-32 px-3 py-2 text-xs text-[var(--text-primary)]
                       bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg
                       resize-none focus:outline-none focus:ring-2 focus:ring-[var(--accent)]
                       focus:border-transparent placeholder:text-[var(--text-muted)]"
            placeholder="Describe what's wrong and how to fix it..."
            value={feedback}
            onChange={(e) => setFeedback(e.target.value)}
            autoFocus
          />
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-[var(--border)] flex justify-end gap-2">
          <button
            onClick={handleCancel}
            className="px-3 py-1.5 text-xs font-medium text-[var(--text-secondary)]
                       hover:text-[var(--text-primary)] rounded-md
                       hover:bg-[var(--bg-hover)] transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={!feedback.trim()}
            className="px-3 py-1.5 text-xs font-medium text-white bg-red-600
                       hover:bg-red-700 rounded-md transition-colors
                       disabled:opacity-40 disabled:cursor-not-allowed"
          >
            Submit Feedback
          </button>
        </div>
      </div>
    </div>
  );
}
