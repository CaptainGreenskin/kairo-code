const STORAGE_KEY = 'kairo-prompt-templates';

export interface PromptTemplate {
    id: string;
    name: string;
    content: string;
    createdAt: number;
}

export function getTemplates(): PromptTemplate[] {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]') as PromptTemplate[];
    } catch {
        return [];
    }
}

export function saveTemplate(name: string, content: string): PromptTemplate {
    const templates = getTemplates();
    const template: PromptTemplate = {
        id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        name: name.trim(),
        content: content.trim(),
        createdAt: Date.now(),
    };
    templates.push(template);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(templates));
    return template;
}

export function deleteTemplate(id: string): void {
    const templates = getTemplates().filter(t => t.id !== id);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(templates));
}

export function updateTemplate(id: string, name: string, content: string): void {
    const templates = getTemplates().map(t =>
        t.id === id ? { ...t, name: name.trim(), content: content.trim() } : t,
    );
    localStorage.setItem(STORAGE_KEY, JSON.stringify(templates));
}
