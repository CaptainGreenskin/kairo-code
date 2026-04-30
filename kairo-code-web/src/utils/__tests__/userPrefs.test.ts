import { describe, it, expect, beforeEach } from 'vitest';
import { loadPrefs, savePrefs, savePref } from '../userPrefs';

describe('userPrefs', () => {
    beforeEach(() => localStorage.clear());

    it('returns empty object when nothing saved', () => {
        expect(loadPrefs()).toEqual({});
    });

    it('saves and loads theme pref', () => {
        savePref('theme', 'dark');
        expect(loadPrefs().theme).toBe('dark');
    });

    it('saves and loads model pref', () => {
        savePref('model', 'gpt-4o');
        expect(loadPrefs().model).toBe('gpt-4o');
    });

    it('merges partial prefs without overwriting others', () => {
        savePref('theme', 'light');
        savePref('model', 'gpt-4o');
        expect(loadPrefs()).toMatchObject({ theme: 'light', model: 'gpt-4o' });
    });

    it('overwrites existing pref with same key', () => {
        savePref('theme', 'light');
        savePref('theme', 'dark');
        expect(loadPrefs().theme).toBe('dark');
    });

    it('savePrefs merges multiple keys at once', () => {
        savePrefs({ theme: 'dark', sidebarWidth: 300 });
        const prefs = loadPrefs();
        expect(prefs.theme).toBe('dark');
        expect(prefs.sidebarWidth).toBe(300);
    });

    it('returns empty object on corrupt localStorage', () => {
        localStorage.setItem('kairo-user-prefs', 'not-json');
        expect(loadPrefs()).toEqual({});
    });
});
