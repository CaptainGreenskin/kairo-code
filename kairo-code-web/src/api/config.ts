import type { ServerConfig, SessionInfo, SessionIndexEntry, FileEntry, FileContentResponse, SearchResponse } from '@/types/agent';

const API_BASE = '/api';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${API_BASE}${path}`, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
    return response.json() as Promise<T>;
}

export async function getConfig(): Promise<ServerConfig> {
    return request<ServerConfig>('/config');
}

export async function getModels(): Promise<string[]> {
    return request<string[]>('/models');
}

export interface ProviderInfo {
    id: string;
    displayName: string;
    defaultBaseUrl: string;
    defaultModel: string;
    knownModels: string[];
}

export async function getProviders(): Promise<ProviderInfo[]> {
    return request<ProviderInfo[]>('/providers');
}

export async function deleteSession(sessionId: string): Promise<void> {
    return request<void>(`/sessions/${sessionId}`, { method: 'DELETE' });
}

export async function listSessions(): Promise<SessionInfo[]> {
    return request<SessionInfo[]>('/sessions');
}

export async function fetchSessionIndex(): Promise<SessionIndexEntry[]> {
    return request<SessionIndexEntry[]>('/sessions/index');
}

function buildQuery(params: Record<string, string | number | undefined>): string {
    const usp = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null && v !== '') usp.set(k, String(v));
    }
    const s = usp.toString();
    return s ? `?${s}` : '';
}

export async function listFiles(path?: string, workspaceId?: string): Promise<FileEntry[]> {
    return request<FileEntry[]>(`/files${buildQuery({ path, workspaceId })}`);
}

export async function getFileContent(path: string, workspaceId?: string): Promise<FileContentResponse> {
    return request<FileContentResponse>(`/files/content${buildQuery({ path, workspaceId })}`);
}

export interface UpdateConfigRequest {
    apiKey?: string;
    model?: string;
    provider?: string;
    baseUrl?: string;
    thinkingBudget?: number | null;
}

export async function updateConfig(req: UpdateConfigRequest): Promise<ServerConfig> {
    return request<ServerConfig>('/config', {
        method: 'POST',
        body: JSON.stringify(req),
    });
}

export interface SearchMatch {
    file: string;
    line: number;
    preview: string;
    beforeContext?: string[];
    afterContext?: string[];
}

export interface SearchOptions {
    q: string;
    path?: string;
    limit?: number;
    workspaceId?: string;
    regex?: boolean;
    caseSensitive?: boolean;
    include?: string;
    exclude?: string;
    contextLines?: number;
}

export async function searchFiles(opts: SearchOptions): Promise<SearchResponse>;
export async function searchFiles(q: string, path?: string, limit?: number, workspaceId?: string): Promise<SearchResponse>;
export async function searchFiles(qOrOpts: string | SearchOptions, path?: string, limit?: number, workspaceId?: string): Promise<SearchResponse> {
    const params = new URLSearchParams();
    if (typeof qOrOpts === 'string') {
        params.set('q', qOrOpts);
        if (path) params.set('path', path);
        if (limit) params.set('limit', String(limit));
        if (workspaceId) params.set('workspaceId', workspaceId);
    } else {
        params.set('q', qOrOpts.q);
        if (qOrOpts.path) params.set('path', qOrOpts.path);
        if (qOrOpts.limit) params.set('limit', String(qOrOpts.limit));
        if (qOrOpts.workspaceId) params.set('workspaceId', qOrOpts.workspaceId);
        if (qOrOpts.regex) params.set('regex', 'true');
        if (qOrOpts.caseSensitive) params.set('caseSensitive', 'true');
        if (qOrOpts.include) params.set('include', qOrOpts.include);
        if (qOrOpts.exclude) params.set('exclude', qOrOpts.exclude);
        if (qOrOpts.contextLines) params.set('contextLines', String(qOrOpts.contextLines));
    }
    return request<SearchResponse>(`/search?${params}`);
}

export async function searchFileNames(q: string, limit?: number, workspaceId?: string): Promise<string[]> {
    const params = new URLSearchParams();
    params.set('q', q);
    if (limit) params.set('limit', String(limit));
    if (workspaceId) params.set('workspaceId', workspaceId);
    return request<string[]>(`/search/files?${params}`);
}

export interface SymbolResult {
    name: string;
    kind: string;
    file: string;
    line: number;
    preview: string;
}

export async function searchSymbols(q: string, limit?: number, workspaceId?: string): Promise<SymbolResult[]> {
    const params = new URLSearchParams();
    params.set('q', q);
    if (limit) params.set('limit', String(limit));
    if (workspaceId) params.set('workspaceId', workspaceId);
    return request<SymbolResult[]>(`/search/symbols?${params}`);
}

export interface DirEntry {
    name: string;
    path: string;
}

export async function getDirs(path: string): Promise<DirEntry[]> {
    return request<DirEntry[]>(`/dirs?path=${encodeURIComponent(path)}`);
}

export async function chooseDir(): Promise<string | null> {
    try {
        const res = await request<{ path: string }>('/choose-dir');
        return res.path || null;
    } catch {
        return null;
    }
}

export async function putFileContent(path: string, content: string, workspaceId?: string): Promise<void> {
    const response = await fetch(`/api/files/content${buildQuery({ path, workspaceId })}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: content,
    });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
}

export async function deleteFile(path: string, workspaceId?: string): Promise<void> {
    const response = await fetch(`/api/files${buildQuery({ path, workspaceId })}`, { method: 'DELETE' });
    if (!response.ok && response.status !== 204) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
}

export async function renameFile(from: string, to: string, workspaceId?: string): Promise<void> {
    return request<void>(`/files/rename${buildQuery({ from, to, workspaceId })}`, { method: 'POST' });
}

export async function createDir(path: string, workspaceId?: string): Promise<void> {
    return request<void>(`/files/mkdir${buildQuery({ path, workspaceId })}`, { method: 'POST' });
}

export async function createFile(path: string, workspaceId?: string): Promise<void> {
    return putFileContent(path, '', workspaceId);
}

export async function renameSession(id: string, name: string): Promise<boolean> {
    const res = await fetch(`/api/sessions/${id}/name`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
    });
    return res.ok;
}
