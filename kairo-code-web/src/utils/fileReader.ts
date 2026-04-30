const MAX_FILE_SIZE = 512 * 1024; // 512 KB
const MAX_LINES = 500;

export interface ReadFileResult {
    name: string;
    language: string;
    content: string;
    truncated: boolean;
}

const EXT_TO_LANG: Record<string, string> = {
    ts: 'typescript', tsx: 'tsx', js: 'javascript', jsx: 'jsx',
    py: 'python', java: 'java', kt: 'kotlin', go: 'go',
    rs: 'rust', cpp: 'cpp', c: 'c', cs: 'csharp',
    json: 'json', yaml: 'yaml', yml: 'yaml', toml: 'toml',
    xml: 'xml', html: 'html', css: 'css', scss: 'scss',
    sh: 'bash', bash: 'bash', zsh: 'bash',
    md: 'markdown', txt: 'text', log: 'text', sql: 'sql',
    dockerfile: 'dockerfile', makefile: 'makefile',
};

export function detectLanguage(filename: string): string {
    const lower = filename.toLowerCase();
    if (lower === 'dockerfile' || lower === 'makefile') return lower;
    const ext = lower.split('.').pop() ?? '';
    return EXT_TO_LANG[ext] ?? 'text';
}

/**
 * Reads a File object and returns its content (truncated if too large).
 */
export async function readFile(file: File): Promise<ReadFileResult> {
    const language = detectLanguage(file.name);

    if (file.size > MAX_FILE_SIZE) {
        return {
            name: file.name,
            language,
            content: `[File too large: ${(file.size / 1024).toFixed(0)} KB, max ${MAX_FILE_SIZE / 1024} KB]`,
            truncated: true,
        };
    }

    const text = await file.text();
    const lines = text.split('\n');
    const truncated = lines.length > MAX_LINES;
    const content = truncated ? lines.slice(0, MAX_LINES).join('\n') + '\n... (truncated)' : text;

    return { name: file.name, language, content, truncated };
}

/**
 * Formats a ReadFileResult as a Markdown code block with filename comment.
 */
export function formatFileBlock(result: ReadFileResult): string {
    return `\`\`\`${result.language}\n// ${result.name}${result.truncated ? ' (truncated)' : ''}\n${result.content}\n\`\`\`\n`;
}
