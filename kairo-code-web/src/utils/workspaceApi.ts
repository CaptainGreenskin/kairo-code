export interface Workspace {
    id: string;
    name: string;
    workingDir: string;
    useWorktree: boolean;
    createdAt: number;
}

export interface WorkspaceCreateRequest {
    name: string;
    workingDir: string;
    useWorktree?: boolean;
}

export interface WorkspaceUpdateRequest {
    name?: string;
    workingDir?: string;
    useWorktree?: boolean;
}

const API_BASE = '/api/workspaces';

async function jsonOrThrow<T>(res: Response): Promise<T> {
    if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
    }
    return res.json();
}

export async function listWorkspaces(): Promise<Workspace[]> {
    const res = await fetch(API_BASE);
    return jsonOrThrow<Workspace[]>(res);
}

export async function createWorkspace(req: WorkspaceCreateRequest): Promise<Workspace> {
    const res = await fetch(API_BASE, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
    });
    return jsonOrThrow<Workspace>(res);
}

export async function updateWorkspace(id: string, req: WorkspaceUpdateRequest): Promise<Workspace> {
    const res = await fetch(`${API_BASE}/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
    });
    return jsonOrThrow<Workspace>(res);
}

export async function deleteWorkspace(id: string): Promise<void> {
    const res = await fetch(`${API_BASE}/${encodeURIComponent(id)}`, {
        method: 'DELETE',
    });
    if (!res.ok && res.status !== 204) {
        const text = await res.text().catch(() => '');
        throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
    }
}

export async function getWorkspaceSessions(id: string): Promise<unknown[]> {
    const res = await fetch(`${API_BASE}/${encodeURIComponent(id)}/sessions`);
    return jsonOrThrow<unknown[]>(res);
}
