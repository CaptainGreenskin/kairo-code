import { describe, it, expect } from 'vitest';
import { parseAutocompleteState, filterCommands, applyAutocomplete, SLASH_COMMANDS } from '../autocomplete';

describe('autocomplete', () => {
    describe('parseAutocompleteState', () => {
        it('detects slash command at start', () => {
            const s = parseAutocompleteState('/hel', 4);
            expect(s.type).toBe('slash');
            expect(s.query).toBe('hel');
        });

        it('detects slash after newline', () => {
            const s = parseAutocompleteState('some text\n/cl', 13);
            expect(s.type).toBe('slash');
            expect(s.query).toBe('cl');
        });

        it('returns none for slash in middle of word', () => {
            const s = parseAutocompleteState('http://example', 14);
            expect(s.type).toBe('none');
        });

        it('detects @mention', () => {
            const s = parseAutocompleteState('look at @src/App', 16);
            expect(s.type).toBe('mention');
            expect(s.query).toBe('src/App');
        });

        it('returns none for plain text', () => {
            const s = parseAutocompleteState('hello world', 11);
            expect(s.type).toBe('none');
        });

        it('returns none for empty input', () => {
            const s = parseAutocompleteState('', 0);
            expect(s.type).toBe('none');
        });
    });

    describe('filterCommands', () => {
        it('filters by prefix', () => {
            const result = filterCommands(SLASH_COMMANDS, 'hel');
            expect(result.some(c => c.name === 'help')).toBe(true);
        });

        it('is case-insensitive', () => {
            const result = filterCommands(SLASH_COMMANDS, 'HEL');
            expect(result.some(c => c.name === 'help')).toBe(true);
        });

        it('returns all commands for empty query', () => {
            expect(filterCommands(SLASH_COMMANDS, '')).toHaveLength(SLASH_COMMANDS.length);
        });

        it('returns empty for no match', () => {
            expect(filterCommands(SLASH_COMMANDS, 'zzz')).toHaveLength(0);
        });
    });

    describe('applyAutocomplete', () => {
        it('replaces trigger+query with replacement', () => {
            const state = { type: 'slash' as const, query: 'hel', startIndex: 0 };
            const { newValue, newCursorPos } = applyAutocomplete('/hel', 4, state, '/help ');
            expect(newValue).toBe('/help ');
            expect(newCursorPos).toBe(6);
        });

        it('preserves text after cursor', () => {
            const state = { type: 'mention' as const, query: 'App', startIndex: 8 };
            const { newValue } = applyAutocomplete('look at @kairo-code-core/src/main/java/io/kairo/code/core/hook/SessionAppendHook.java rest', 12, state, '@App.tsx ');
            expect(newValue).toContain('rest');
            expect(newValue).toContain('@App.tsx');
        });
    });
});
