import type { ServerConfig, SessionInfo } from '@/types/agent';

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

export async function createSession(
    workingDir: string,
    model: string,
): Promise<{ sessionId: string }> {
    return request<{ sessionId: string }>('/sessions', {
        method: 'POST',
        body: JSON.stringify({ workingDir, model }),
    });
}

export async function deleteSession(sessionId: string): Promise<void> {
    return request<void>(`/sessions/${sessionId}`, { method: 'DELETE' });
}

export async function listSessions(): Promise<SessionInfo[]> {
    return request<SessionInfo[]>('/sessions');
}
