import { useState, useRef, useEffect, useCallback } from 'react';
import { Send, Square, Paperclip, X, ClipboardList, Plus } from 'lucide-react';
import { FilePicker } from './FilePicker';
import { AutocompleteDropdown } from './AutocompleteDropdown';
import { ApprovalModeToggle } from './ApprovalModeToggle';
import { ModelSelector } from './ModelSelector';
import { PlanModeBadge } from './PlanModeBadge';
import { ContextHealthIndicator } from './ContextHealthIndicator';
import { StallIndicator } from './StallIndicator';
import { parseAutocompleteState, filterCommands, applyAutocomplete, SLASH_COMMANDS, type AutocompleteState } from '@utils/autocomplete';
import { getHistory, pushHistory } from '@utils/inputHistory';
import { saveDraft, clearDraft } from '@utils/inputDraft';
import { readFile, formatFileBlock } from '@utils/fileReader';
import { listFiles } from '@api/config';
import { usePlanModeStore, PLAN_MODE_PREAMBLE } from '@store/planModeStore';
import type { FileEntry } from '@/types/agent';

export interface AttachedImage {
    data: string;       // base64 (no data: URL prefix)
    mediaType: string;  // e.g. "image/png"
    preview: string;    // object URL for preview
}

interface ChatInputProps {
    onSend: (text: string, image: AttachedImage | null) => void;
    onInterruptAndSend?: (text: string, image: AttachedImage | null) => void;
    onStop: () => void;
    disabled: boolean;
    isThinking: boolean;
    /** Backend agent loop is active. Distinct from {@link isThinking} which flickers
     *  between iterations. Drives the Send/Stop swap so the button doesn't bounce
     *  mid-iteration. Falls back to isThinking when undefined for older callers. */
    running?: boolean;
    appendText?: string;
    onAppendConsumed?: () => void;
    sessionId?: string;
    initialDraft?: string;
    pendingToolCount?: number;
    autoApproveTools?: string[];
    onScrollToPending?: () => void;
    /** Footer toolbar: model picker. Same data as the Header used to receive. */
    models?: string[];
    currentModel?: string | null;
    onModelChange?: (model: string) => void;
    /** Above-strip: context window % chip. Hidden when contextWindow is 0/undefined. */
    tokenUsage?: { input: number; output: number };
    contextWindow?: number;
    isCompacting?: boolean;
    /** Above-strip: "+ New" button — open a new chat tab. */
    onNewChat?: () => void;
}

const AT_RE = /@([^\s]*)$/;
const CHAR_WARN_THRESHOLD = 2000;
const CHAR_MAX = 4000;

function readFileAsBase64(file: File): Promise<{ data: string; mediaType: string }> {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const dataUrl = reader.result as string;
            const [header, data] = dataUrl.split(',');
            const mediaType = header.match(/data:(.*);/)?.[1] ?? 'image/png';
            resolve({ data, mediaType });
        };
        reader.onerror = reject;
        reader.readAsDataURL(file);
    });
}

export function ChatInput({
    onSend,
    onInterruptAndSend,
    onStop,
    disabled,
    isThinking,
    running,
    appendText,
    onAppendConsumed,
    sessionId,
    initialDraft,
    pendingToolCount = 0,
    autoApproveTools = [],
    onScrollToPending,
    models = [],
    currentModel = null,
    onModelChange,
    tokenUsage,
    contextWindow,
    isCompacting = false,
    onNewChat,
}: ChatInputProps) {
    const [text, setText] = useState(initialDraft ?? '');
    const [dragging, setDragging] = useState(false);
    const [isDragOver, setIsDragOver] = useState(false);
    const [showFilePicker, setShowFilePicker] = useState(false);
    const [atQuery, setAtQuery] = useState('');
    const [attachedImage, setAttachedImage] = useState<AttachedImage | null>(null);
    const historyIndexRef = useRef<number | null>(null);
    const draftRef = useRef('');
    const textareaRef = useRef<HTMLTextAreaElement>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);
    // True while an IME (Chinese pinyin / Japanese kana / Korean hangul) is composing —
    // Enter during composition picks a candidate and must not submit the message.
    const composingRef = useRef(false);

    // Autocomplete state
    const [acState, setAcState] = useState<AutocompleteState>({ type: 'none', query: '', startIndex: -1 });
    const [acSelectedIndex, setAcSelectedIndex] = useState(0);
    const [fileSuggestions, setFileSuggestions] = useState<FileEntry[]>([]);
    const prevAcQueryRef = useRef('');

    // Plan mode (Claude Code parity). `pending` = user pressed Shift+Tab and the next
    // message will be wrapped with the plan-mode preamble. `active` = backend is in
    // plan mode (read-only enforcement). Both are independent — see planModeStore.ts.
    const planPending = usePlanModeStore((s) => s.pending);
    const planActive = usePlanModeStore((s) => s.active);
    const togglePlanPending = usePlanModeStore((s) => s.togglePending);
    const setPlanPending = usePlanModeStore((s) => s.setPending);

    useEffect(() => {
        textareaRef.current?.focus();
    }, []);

    // Fetch file suggestions when @mention query changes
    useEffect(() => {
        if (acState.type === 'mention') {
            const queryKey = acState.query;
            if (queryKey === prevAcQueryRef.current) return;
            prevAcQueryRef.current = queryKey;
            fetchFileSuggestions(queryKey).then(setFileSuggestions);
        } else {
            prevAcQueryRef.current = '';
            setFileSuggestions([]);
        }
    }, [acState.type, acState.query]);

    // Reset history navigation when sessionId changes
    useEffect(() => {
        historyIndexRef.current = null;
        draftRef.current = '';
    }, [sessionId]);

    // Save draft to localStorage with debounce
    useEffect(() => {
        if (!sessionId) return;
        const timer = setTimeout(() => saveDraft(sessionId, text), 300);
        return () => clearTimeout(timer);
    }, [text, sessionId]);

    // Auto-adjust textarea height
    useEffect(() => {
        const el = textareaRef.current;
        if (!el) return;
        el.style.height = 'auto';
        el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
    }, [text]);

    // Handle external text append (e.g. from file tree panel)
    useEffect(() => {
        if (appendText) {
            setText(prev => prev + appendText);
            onAppendConsumed?.();
            textareaRef.current?.focus();
        }
    }, [appendText, onAppendConsumed]);

    // Detect @ trigger on text change
    useEffect(() => {
        // Find cursor position
        const ta = textareaRef.current;
        if (!ta) return;
        const cursorPos = ta.selectionStart;
        const textBeforeCursor = text.substring(0, cursorPos);
        const match = textBeforeCursor.match(AT_RE);
        if (match) {
            setAtQuery(match[1]);
            setShowFilePicker(true);
        } else {
            setShowFilePicker(false);
            setAtQuery('');
        }
    }, [text]);

    // Autocomplete: fetch file suggestions for @mention
    async function fetchFileSuggestions(query: string): Promise<FileEntry[]> {
        try {
            // Parse query to determine directory and filter
            const lastSlash = query.lastIndexOf('/');
            const dir = lastSlash >= 0 ? query.substring(0, lastSlash) : '';
            const nameFilter = lastSlash >= 0 ? query.substring(lastSlash + 1) : query;

            const entries = await listFiles(dir || undefined);
            if (nameFilter) {
                return entries.filter(e => e.name.toLowerCase().startsWith(nameFilter.toLowerCase()));
            }
            return entries;
        } catch {
            return [];
        }
    }

    // Autocomplete: get items for current autocomplete state
    function getAcItems() {
        if (acState.type === 'slash') {
            return filterCommands(SLASH_COMMANDS, acState.query).map(c => ({
                label: '/' + c.name,
                description: c.description,
                value: '/' + c.name + ' ',
            }));
        }
        if (acState.type === 'mention') {
            return fileSuggestions.slice(0, 10).map(entry => ({
                label: entry.name,
                description: entry.path,
                value: '@' + entry.path + ' ',
            }));
        }
        return [];
    }

    // Autocomplete: handle selecting an item
    function handleAcSelect(item: { label: string; value: string; description?: string }) {
        const cursor = textareaRef.current?.selectionStart ?? text.length;
        const { newValue, newCursorPos } = applyAutocomplete(text, cursor, acState, item.value);
        setText(newValue);
        setAcState({ type: 'none', query: '', startIndex: -1 });
        // Restore cursor position after React re-render
        setTimeout(() => {
            if (textareaRef.current) {
                textareaRef.current.selectionStart = newCursorPos;
                textareaRef.current.selectionEnd = newCursorPos;
                textareaRef.current.focus();
            }
        }, 0);
    }

    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
        // Shift+Tab: toggle plan mode for the next message (Claude Code shortcut).
        // Must come before autocomplete's Tab handler so it can't be hijacked.
        if (e.key === 'Tab' && e.shiftKey && acState.type === 'none') {
            e.preventDefault();
            togglePlanPending();
            return;
        }

        // Autocomplete navigation (take priority when active)
        if (acState.type !== 'none') {
            const items = getAcItems();
            if (e.key === 'ArrowUp' && !e.metaKey && !e.ctrlKey) {
                e.preventDefault();
                setAcSelectedIndex(i => Math.max(0, i - 1));
                return;
            }
            if (e.key === 'ArrowDown' && !e.metaKey && !e.ctrlKey) {
                e.preventDefault();
                setAcSelectedIndex(i => Math.min(items.length - 1, i + 1));
                return;
            }
            if (e.key === 'Enter' && items.length > 0 && !e.shiftKey) {
                // IME composition: Enter is confirming a pinyin candidate, not picking
                // an autocomplete item. Let the browser handle it normally.
                if (e.nativeEvent.isComposing || e.keyCode === 229 || composingRef.current) {
                    return;
                }
                e.preventDefault();
                handleAcSelect(items[acSelectedIndex]);
                return;
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                setAcState({ type: 'none', query: '', startIndex: -1 });
                return;
            }
            if (e.key === 'Tab' && items.length > 0) {
                e.preventDefault();
                handleAcSelect(items[0]);
                return;
            }
        }

        // History navigation: Cmd+ArrowUp / Ctrl+ArrowUp
        if (e.key === 'ArrowUp' && !e.shiftKey && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            if (!sessionId) return;
            const history = getHistory(sessionId);
            if (history.length === 0) return;
            if (historyIndexRef.current === null) {
                draftRef.current = text;
                const newIndex = history.length - 1;
                historyIndexRef.current = newIndex;
                setText(history[newIndex]);
            } else if (historyIndexRef.current > 0) {
                const newIndex = historyIndexRef.current - 1;
                historyIndexRef.current = newIndex;
                setText(history[newIndex]);
            }
            return;
        }

        // History navigation: Cmd+ArrowDown / Ctrl+ArrowDown
        if (e.key === 'ArrowDown' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            if (historyIndexRef.current === null || !sessionId) return;
            const history = getHistory(sessionId);
            if (historyIndexRef.current < history.length - 1) {
                const newIndex = historyIndexRef.current + 1;
                historyIndexRef.current = newIndex;
                setText(history[newIndex]);
            } else {
                historyIndexRef.current = null;
                setText(draftRef.current);
            }
            return;
        }

        // Exit history: Escape
        if (e.key === 'Escape' && historyIndexRef.current !== null) {
            e.preventDefault();
            historyIndexRef.current = null;
            setText(draftRef.current);
            return;
        }

        // Suppress Enter while an IME is composing — Enter is confirming a candidate.
        // Browsers expose this via `isComposing`; older ones report keyCode 229 instead.
        if (e.key === 'Enter' && (e.nativeEvent.isComposing || e.keyCode === 229 || composingRef.current)) {
            return;
        }

        // Cmd+Enter / Ctrl+Enter: interrupt and send
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            const trimmed = text.trim();
            if (trimmed) {
                historyIndexRef.current = null;
                draftRef.current = '';
                setText('');
                setShowFilePicker(false);
                const img = attachedImage;
                setAttachedImage(null);
                const finalText = planPending ? PLAN_MODE_PREAMBLE + trimmed : trimmed;
                if (planPending) setPlanPending(false);
                onInterruptAndSend?.(finalText, img);
            }
            return;
        }

        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    const handleSend = () => {
        const trimmed = text.trim();
        if (!trimmed || disabled) return;
        if (sessionId) pushHistory(sessionId, trimmed);
        historyIndexRef.current = null;
        draftRef.current = '';
        if (sessionId) clearDraft(sessionId);
        const img = attachedImage;
        setAttachedImage(null);
        // Plan mode: prepend the preamble so the agent knows to enter_plan_mode and
        // research read-only before exit_plan_mode. Consumed once, then cleared.
        const finalText = planPending ? PLAN_MODE_PREAMBLE + trimmed : trimmed;
        if (planPending) setPlanPending(false);
        onSend(finalText, img);
        setText('');
        setShowFilePicker(false);
    };

    const handleDrop = async (e: React.DragEvent) => {
        e.preventDefault();
        setDragging(false);
        setIsDragOver(false);
        const files = Array.from(e.dataTransfer.files);
        if (files.length === 0) return;

        // Separate image files from non-image files
        const imageFiles = files.filter(f => f.type.startsWith('image/'));
        const otherFiles = files.filter(f => !f.type.startsWith('image/'));

        // If there's exactly one image file and no other files, attach it as image
        if (imageFiles.length === 1 && otherFiles.length === 0) {
            const file = imageFiles[0];
            const { data, mediaType } = await readFileAsBase64(file);
            setAttachedImage({ data, mediaType, preview: URL.createObjectURL(file) });
            return;
        }

        // Otherwise, insert all files as code blocks (existing behavior)
        const blocks = await Promise.all(files.map(readFile));
        const inserted = blocks.map(formatFileBlock).join('\n');
        setText(prev => prev ? prev + '\n' + inserted : inserted);
    };

    const handleDragOver = (e: React.DragEvent) => {
        e.preventDefault();
        setDragging(true);
        setIsDragOver(true);
    };

    const handleDragLeave = (e: React.DragEvent) => {
        e.preventDefault();
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        const x = e.clientX;
        const y = e.clientY;
        if (x < rect.left || x >= rect.right || y < rect.top || y >= rect.bottom) {
            setDragging(false);
            setIsDragOver(false);
        }
    };

    const handlePaste = async (e: React.ClipboardEvent) => {
        const items = Array.from(e.clipboardData.items);
        const imageItem = items.find(i => i.type.startsWith('image/'));
        if (imageItem) {
            e.preventDefault();
            const file = imageItem.getAsFile();
            if (!file) return;
            const { data, mediaType } = await readFileAsBase64(file);
            setAttachedImage({ data, mediaType, preview: URL.createObjectURL(file) });
            return;
        }

        // Fall back to existing file paste behavior
        const files = Array.from(e.clipboardData.files);
        if (files.length > 0) {
            e.preventDefault();
            const blocks = await Promise.all(files.map(readFile));
            const text = blocks.map(formatFileBlock).join('\n');
            setText(prev => prev ? prev + '\n' + text : text);
            return;
        }
    };

    const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        const { data, mediaType } = await readFileAsBase64(file);
        setAttachedImage({ data, mediaType, preview: URL.createObjectURL(file) });
        // Reset input so the same file can be selected again
        e.target.value = '';
    };

    const handleRemoveImage = () => {
        if (attachedImage?.preview) {
            URL.revokeObjectURL(attachedImage.preview);
        }
        setAttachedImage(null);
    };

    const handleFilePickerSelect = useCallback((block: string) => {
        // Replace the @query with the file content block
        const ta = textareaRef.current;
        if (!ta) {
            setText((prev) => prev + block);
            setShowFilePicker(false);
            return;
        }
        const cursorPos = ta.selectionStart;
        const textBeforeCursor = text.substring(0, cursorPos);
        const match = textBeforeCursor.match(AT_RE);
        if (match) {
            const atStart = cursorPos - match[0].length;
            const newText = text.substring(0, atStart) + block + text.substring(cursorPos);
            setText(newText);
        } else {
            setText((prev) => prev + block);
        }
        setShowFilePicker(false);
    }, [text]);

    const handleFilePickerClose = useCallback(() => {
        setShowFilePicker(false);
    }, []);

    const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
        setText(e.target.value);
        // Re-parse autocomplete state after text change
        const cursor = e.target.selectionStart ?? 0;
        const newAcState = parseAutocompleteState(e.target.value, cursor);
        setAcState(newAcState);
        setAcSelectedIndex(0);
    };

    return (
        <div
            className={`px-4 pb-3 pt-1 transition-all ${
                dragging ? 'ring-2 ring-[var(--color-primary)]' : ''
            }`}
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
        >
            {/* Plan-mode indicator row. `active` = backend is in plan mode (read-only).
                `pending` = user pressed Shift+Tab; next message will request plan mode. */}
            {(planActive || planPending) && (
                <div
                    className={`flex items-center gap-2 text-xs px-3 py-1.5 mb-2 rounded border ${
                        planActive
                            ? 'border-purple-500/40 bg-purple-500/10 text-purple-300'
                            : 'border-purple-500/20 bg-purple-500/5 text-purple-300/80'
                    }`}
                >
                    <ClipboardList size={13} className="shrink-0" />
                    <span className="flex-1">
                        {planActive
                            ? 'Plan mode active — agent is researching read-only'
                            : 'Plan mode armed — next message will start research'}
                    </span>
                    {planPending && (
                        <button
                            onClick={() => setPlanPending(false)}
                            className="text-[10px] underline hover:no-underline opacity-70 hover:opacity-100"
                        >
                            cancel
                        </button>
                    )}
                    <kbd className="px-1 py-0.5 rounded border border-purple-500/30 bg-purple-500/10 font-mono text-[10px]">
                        ⇧Tab
                    </kbd>
                </div>
            )}

            {/* AutoApprove transparency row */}
            {(pendingToolCount > 0 || autoApproveTools.length > 0) && (
                <div
                    className={`text-xs px-4 py-1 mb-2 cursor-pointer transition-colors ${
                        pendingToolCount > 0
                            ? 'text-amber-400 hover:text-amber-300'
                            : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                    }`}
                    onClick={() => pendingToolCount > 0 && onScrollToPending?.()}
                    title={pendingToolCount > 0 ? 'Click to scroll to pending tool' : undefined}
                >
                    {pendingToolCount > 0
                        ? `\u{1F7E1} Pending approval: ${pendingToolCount} tool${pendingToolCount > 1 ? 's' : ''}`
                        : `\u2705 Auto-approving: ${autoApproveTools.join(', ')}`}
                </div>
            )}

            {/* Unified Qoder-style card: top chips + textarea + footer toolbar all share
                one rounded border + background. Textarea is borderless inside. */}
            <div
                className={`relative rounded-xl bg-[var(--bg-secondary)] transition-colors ${
                    isDragOver
                        ? 'ring-1 ring-[var(--accent)]'
                        : 'ring-1 ring-transparent focus-within:ring-[var(--text-muted)]/40'
                }`}
            >
                {/* Top strip — context % + New Chat. Lives inside the card, right-aligned. */}
                {((tokenUsage && contextWindow && contextWindow > 0) || onNewChat) && (
                    <div className="flex items-center justify-end gap-1.5 px-3 pt-2">
                        {tokenUsage && contextWindow && contextWindow > 0 && (
                            <ContextHealthIndicator
                                tokenUsage={tokenUsage.input + tokenUsage.output}
                                maxTokens={contextWindow}
                                isCompacting={isCompacting}
                            />
                        )}
                        {onNewChat && (
                            <button
                                onClick={onNewChat}
                                className="flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
                                title="Start a new chat tab"
                            >
                                <Plus size={11} />
                                New Chat
                            </button>
                        )}
                    </div>
                )}

                <StallIndicator onForceCancel={onStop} />

                {attachedImage && (
                    <div className="relative inline-block px-3 pt-2">
                        <img src={attachedImage.preview} className="max-h-32 rounded border" alt="attachment" />
                        <button
                            onClick={handleRemoveImage}
                            className="absolute top-1 right-1 bg-black/60 text-white rounded-full p-0.5 hover:bg-black/80"
                            aria-label="Remove image"
                        >
                            <X size={12} />
                        </button>
                    </div>
                )}

                <textarea
                    ref={textareaRef}
                    value={text}
                    onChange={handleChange}
                    onKeyDown={handleKeyDown}
                    onCompositionStart={() => { composingRef.current = true; }}
                    onCompositionEnd={() => { composingRef.current = false; }}
                    onPaste={handlePaste}
                    placeholder={
                        historyIndexRef.current !== null
                            ? 'Cmd+↑/↓ history · Esc to cancel'
                            : planPending
                                ? 'Plan mode · describe what to plan'
                                : 'Plan and build, @ for context, ⇧Tab for plan mode'
                    }
                    disabled={disabled && !isThinking}
                    rows={1}
                    className="w-full resize-none overflow-y-auto bg-transparent border-0 px-3 py-2.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-0 disabled:opacity-50"
                    style={{ maxHeight: '240px', minHeight: '64px' }}
                />

                {acState.type !== 'none' && (
                    <AutocompleteDropdown
                        items={getAcItems()}
                        selectedIndex={acSelectedIndex}
                        type={acState.type}
                        onSelect={handleAcSelect}
                    />
                )}
                {text.length > CHAR_WARN_THRESHOLD && (
                    <div className={`absolute right-3 top-2 text-[10px] font-mono pointer-events-none ${
                        text.length > CHAR_MAX * 0.9 ? 'text-red-400' : 'text-[var(--text-muted)]'
                    }`}>
                        {text.length}/{CHAR_MAX}
                    </div>
                )}
                {isDragOver && (
                    <div className="absolute inset-0 flex items-center justify-center bg-[var(--accent)]/10 rounded-xl z-10 pointer-events-none">
                        <span className="text-sm text-[var(--accent)]">Drop files to insert as code blocks</span>
                    </div>
                )}

                {showFilePicker && (
                    <FilePicker
                        query={atQuery}
                        onSelect={handleFilePickerSelect}
                        onClose={handleFilePickerClose}
                    />
                )}

                {/* Footer toolbar — inside the card. Left: approval + model + plan.
                    Right: attach + send. Dropdowns drop UP since input sits at viewport bottom. */}
                <div className="flex items-center justify-between gap-2 px-2 pb-1.5 pt-0">
                    <div className="flex items-center gap-1 min-w-0">
                        <ApprovalModeToggle dropUp />
                        {models.length > 0 && onModelChange && (
                            <ModelSelector
                                models={models}
                                currentModel={currentModel}
                                onChange={onModelChange}
                                disabled={isThinking}
                                dropUp
                            />
                        )}
                        <PlanModeBadge />
                    </div>

                    <div className="flex items-center gap-0.5 shrink-0">
                        <input
                            type="file"
                            accept="image/*"
                            ref={fileInputRef}
                            hidden
                            onChange={handleFileSelect}
                        />
                        <button
                            onClick={() => fileInputRef.current?.click()}
                            disabled={disabled && !isThinking}
                            className="w-7 h-7 flex items-center justify-center rounded-md text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            aria-label="Attach image"
                            title="Attach image"
                        >
                            <Paperclip size={13} />
                        </button>

                        {(running ?? isThinking) ? (
                            <button
                                onClick={onStop}
                                className="w-7 h-7 flex items-center justify-center rounded-md text-[var(--color-danger)] hover:bg-[var(--color-danger)]/15 transition-colors"
                                aria-label="Stop"
                                title="Stop"
                            >
                                <Square size={13} />
                            </button>
                        ) : (
                            <button
                                onClick={handleSend}
                                disabled={disabled || (!text.trim() && !attachedImage)}
                                className={`w-7 h-7 flex items-center justify-center rounded-md transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
                                    text.trim() || attachedImage
                                        ? 'text-[var(--color-primary)] hover:bg-[var(--color-primary)]/15'
                                        : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                                }`}
                                aria-label="Send"
                                title="Send"
                            >
                                <Send size={13} />
                            </button>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
