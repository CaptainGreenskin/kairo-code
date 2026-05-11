import { create } from 'zustand';
import {
    Workspace,
    listWorkspaces as apiList,
    createWorkspace as apiCreate,
    updateWorkspace as apiUpdate,
    deleteWorkspace as apiDelete,
    WorkspaceCreateRequest,
    WorkspaceUpdateRequest,
} from '@/utils/workspaceApi';

const STORAGE_KEY = 'kairo.code.currentWorkspaceId';

interface WorkspaceState {
    workspaces: Workspace[];
    currentWorkspaceId: string | null;
    loaded: boolean;
    loading: boolean;
    error: string | null;

    refresh: () => Promise<Workspace[]>;
    setCurrent: (id: string | null) => void;
    create: (req: WorkspaceCreateRequest) => Promise<Workspace>;
    update: (id: string, req: WorkspaceUpdateRequest) => Promise<Workspace>;
    remove: (id: string) => Promise<void>;
    currentWorkspace: () => Workspace | null;
}

function readStoredId(): string | null {
    try {
        return localStorage.getItem(STORAGE_KEY);
    } catch {
        return null;
    }
}

function writeStoredId(id: string | null) {
    try {
        if (id) localStorage.setItem(STORAGE_KEY, id);
        else localStorage.removeItem(STORAGE_KEY);
    } catch {
        // ignore
    }
}

export const useWorkspaceStore = create<WorkspaceState>((set, get) => ({
    workspaces: [],
    currentWorkspaceId: readStoredId(),
    loaded: false,
    loading: false,
    error: null,

    refresh: async () => {
        set({ loading: true, error: null });
        try {
            const list = await apiList();
            const stored = get().currentWorkspaceId ?? readStoredId();
            const validCurrent = list.find((w) => w.id === stored)?.id
                ?? list[0]?.id
                ?? null;
            if (validCurrent !== stored) writeStoredId(validCurrent);
            set({
                workspaces: list,
                currentWorkspaceId: validCurrent,
                loaded: true,
                loading: false,
            });
            return list;
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : String(e), loaded: true });
            throw e;
        }
    },

    setCurrent: (id) => {
        writeStoredId(id);
        set({ currentWorkspaceId: id });
    },

    create: async (req) => {
        const ws = await apiCreate(req);
        const list = [...get().workspaces, ws];
        writeStoredId(ws.id);
        set({ workspaces: list, currentWorkspaceId: ws.id });
        return ws;
    },

    update: async (id, req) => {
        const ws = await apiUpdate(id, req);
        set({ workspaces: get().workspaces.map((w) => (w.id === id ? ws : w)) });
        return ws;
    },

    remove: async (id) => {
        await apiDelete(id);
        const remaining = get().workspaces.filter((w) => w.id !== id);
        const wasCurrent = get().currentWorkspaceId === id;
        const newCurrent = wasCurrent ? remaining[0]?.id ?? null : get().currentWorkspaceId;
        if (wasCurrent) writeStoredId(newCurrent);
        set({ workspaces: remaining, currentWorkspaceId: newCurrent });
    },

    currentWorkspace: () => {
        const { workspaces, currentWorkspaceId } = get();
        return workspaces.find((w) => w.id === currentWorkspaceId) ?? null;
    },
}));
