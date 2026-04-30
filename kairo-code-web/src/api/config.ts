import type { ServerConfig, SessionInfo } from '@/types/agent';

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
