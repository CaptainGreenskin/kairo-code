export type FileWriteInfo = {
    filePath: string;
    content: string;
    language: string;
};

const WRITE_TOOL_NAMES = new Set([
    'write_file', 'create_file', 'edit_file', 'str_replace_editor',
    'overwrite_file', 'patch_file',
]);

export function extractFileWriteInfo(toolName: string, input: Record<string, unknown>): FileWriteInfo | null {
    if (!WRITE_TOOL_NAMES.has(toolName)) return null;

    const filePath = (input.path ?? input.file_path ?? input.filename ?? '') as string;
    const content = (input.content ?? input.new_content ?? input.file_text ?? '') as string;

    if (!filePath || !content) return null;

    return {
        filePath,
        content,
        language: inferLanguage(filePath),
    };
}

function inferLanguage(filePath: string): string {
    const ext = filePath.split('.').pop()?.toLowerCase() ?? '';
    const map: Record<string, string> = {
        ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript',
        py: 'python', java: 'java', go: 'go', rs: 'rust',
        md: 'markdown', json: 'json', yaml: 'yaml', yml: 'yaml',
        sh: 'bash', css: 'css', html: 'html', xml: 'xml',
    };
    return map[ext] ?? 'text';
}
