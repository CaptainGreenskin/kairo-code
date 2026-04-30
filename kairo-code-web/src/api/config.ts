import type { ServerConfig, SessionInfo, FileEntry, FileContentResponse } from '@/types/agent';

const API_BASE = '/api';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${API_BASE}${path}`, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    });
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
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
