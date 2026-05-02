import type { ServerConfig, SessionInfo, FileEntry, FileContentResponse, SearchResponse } from '@/types/agent';

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

export async function deleteSession(sessionId: string): Promise<void> {
    return request<void>(`/sessions/${sessionId}`, { method: 'DELETE' });
}

export async function listSessions(): Promise<SessionInfo[]> {
    return request<SessionInfo[]>('/sessions');
}

export async function listFiles(path?: string): Promise<FileEntry[]> {
    const query = path ? `?path=${encodeURIComponent(path)}` : '';
    return request<FileEntry[]>(`/files${query}`);
}

export async function getFileContent(path: string): Promise<FileContentResponse> {
    return request<FileContentResponse>(`/files/content?path=${encodeURIComponent(path)}`);
}

export interface UpdateConfigRequest {
    apiKey?: string;
    model?: string;
    provider?: string;
    baseUrl?: string;
    workingDir?: string;
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
}

export async function searchFiles(q: string, path?: string, limit?: number): Promise<SearchResponse> {
    const params = new URLSearchParams();
    params.set('q', q);
    if (path) params.set('path', path);
    if (limit) params.set('limit', String(limit));
    return request<SearchResponse>(`/search?${params}`);
}

export interface DirEntry {
    name: string;
    path: string;
}

export async function getDirs(path: string): Promise<DirEntry[]> {
    return request<DirEntry[]>(`/dirs?path=${encodeURIComponent(path)}`);
}

export async function putFileContent(path: string, content: string): Promise<void> {
    const response = await fetch(`/api/files/content?path=${encodeURIComponent(path)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: content,
    });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
}

export async function deleteFile(path: string): Promise<void> {
    const response = await fetch(`/api/files?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
    if (!response.ok && response.status !== 204) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
}

export async function renameFile(from: string, to: string): Promise<void> {
    return request<void>(`/files/rename?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { method: 'POST' });
}

export async function createDir(path: string): Promise<void> {
    return request<void>(`/files/mkdir?path=${encodeURIComponent(path)}`, { method: 'POST' });
}

export async function createFile(path: string): Promise<void> {
    return putFileContent(path, '');
}
