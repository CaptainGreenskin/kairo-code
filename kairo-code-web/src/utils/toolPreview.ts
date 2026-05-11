export type FileWriteInfo = {
    filePath: string;
    content: string;
    language: string;
};

const WRITE_TOOL_NAMES = new Set([
    'write', 'edit', 'multi_edit',
    'write_file', 'create_file', 'edit_file', 'str_replace_editor',
    'overwrite_file', 'patch_file',
]);

export function extractFileWriteInfo(toolName: string, input: Record<string, unknown>): FileWriteInfo | null {
    if (!WRITE_TOOL_NAMES.has(toolName)) return null;

    const filePath = (input.path ?? input.file_path ?? input.filename ?? '') as string;
    // For write/edit: content from write OR newText from edit (replacement text)
    const content = (input.content ?? input.new_content ?? input.file_text ?? input.newText ?? '') as string;

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

export type ToolRiskLevel = 'read' | 'write' | 'execute' | 'other';

const WRITE_TOOLS = new Set([
    'write_file', 'create_file', 'edit_file', 'str_replace_editor',
    'overwrite_file', 'patch_file', 'delete_file', 'rename_file',
]);

const EXECUTE_TOOLS = new Set([
    'bash', 'execute_command', 'run_code', 'shell', 'terminal',
    'execute_script', 'run_script',
]);

const READ_TOOLS = new Set([
    'read_file', 'list_directory', 'search_files', 'find_files',
    'get_file_info', 'read_url', 'web_search', 'web_fetch',
]);

export function getToolRiskLevel(toolName: string): ToolRiskLevel {
    if (EXECUTE_TOOLS.has(toolName)) return 'execute';
    if (WRITE_TOOLS.has(toolName)) return 'write';
    if (READ_TOOLS.has(toolName)) return 'read';
    return 'other';
}
