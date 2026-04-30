export interface SlashCommand {
    name: string;        // e.g. "help"
    description: string; // e.g. "Show help"
    action?: string;     // optional: what gets inserted, default "/<name>"
}

// 内置命令列表（与 Claude Code CLI 对齐）
export const SLASH_COMMANDS: SlashCommand[] = [
    { name: 'help',    description: 'Show available commands' },
    { name: 'clear',   description: 'Clear conversation history' },
    { name: 'compact', description: 'Compact conversation (summarize)' },
    { name: 'memory',  description: 'Show memory files' },
    { name: 'review',  description: 'Review recent changes (git diff)' },
    { name: 'init',    description: 'Create CLAUDE.md in current directory' },
    { name: 'doctor',  description: 'Check environment and configuration' },
];

/**
 * Filters commands by prefix (case-insensitive).
 */
export function filterCommands(commands: SlashCommand[], query: string): SlashCommand[] {
    const q = query.toLowerCase();
    return commands.filter(c => c.name.toLowerCase().startsWith(q));
}

export interface AutocompleteState {
    type: 'slash' | 'mention' | 'none';
    query: string;       // text after / or @
    startIndex: number;  // position of / or @ in the full text
}

/**
 * Parses the current input value and cursor position to determine autocomplete state.
 * Returns 'none' if cursor is not immediately after a trigger character sequence.
 */
export function parseAutocompleteState(value: string, cursorPos: number): AutocompleteState {
    const before = value.slice(0, cursorPos);

    // Check for @mention: last @ with no space after it
    const mentionMatch = before.match(/@([^\s@]*)$/);
    if (mentionMatch) {
        return { type: 'mention', query: mentionMatch[1], startIndex: before.lastIndexOf('@') };
    }

    // Check for /command: / at start of line or after whitespace
    const slashMatch = before.match(/(^|[\n])\/([^\s/]*)$/);
    if (slashMatch) {
        return { type: 'slash', query: slashMatch[2], startIndex: before.lastIndexOf('/') };
    }

    return { type: 'none', query: '', startIndex: -1 };
}

/**
 * Applies the selected autocomplete item to the current value.
 * Returns the new value and new cursor position.
 */
export function applyAutocomplete(
    value: string,
    cursorPos: number,
    state: AutocompleteState,
    replacement: string,  // e.g. "/help " or "@src/App.tsx "
): { newValue: string; newCursorPos: number } {
    const before = value.slice(0, state.startIndex);
    const after = value.slice(cursorPos);
    const newValue = before + replacement + after;
    const newCursorPos = before.length + replacement.length;
    return { newValue, newCursorPos };
}
