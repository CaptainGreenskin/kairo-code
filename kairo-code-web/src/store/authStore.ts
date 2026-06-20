import { create } from 'zustand';
import { getAuthToken, setAuthToken } from '../api/auth';

interface AuthUser {
    username: string;
    createdAt?: number;
}

interface AuthState {
    user: AuthUser | null;
    isAuthenticated: boolean;
    isLoading: boolean;
    error: string | null;
    login: (username: string, password: string) => Promise<boolean>;
    register: (username: string, password: string, inviteCode?: string) => Promise<boolean>;
    logout: () => void;
    checkAuth: () => Promise<void>;
}

export const useAuthStore = create<AuthState>()((set) => ({
    user: null,
    isAuthenticated: false,
    isLoading: true,
    error: null,

    login: async (username, password) => {
        set({ error: null });
        try {
            const res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password }),
            });
            const data = await res.json();
            if (!res.ok) {
                set({ error: data.message || 'Login failed' });
                return false;
            }
            setAuthToken(data.token);
            set({ user: data.user, isAuthenticated: true, error: null });
            return true;
        } catch (e) {
            set({ error: 'Network error' });
            return false;
        }
    },

    register: async (username, password, inviteCode) => {
        set({ error: null });
        try {
            const res = await fetch('/api/auth/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password, inviteCode: inviteCode || '' }),
            });
            const data = await res.json();
            if (!res.ok) {
                set({ error: data.message || 'Registration failed' });
                return false;
            }
            setAuthToken(data.token);
            set({ user: data.user, isAuthenticated: true, error: null });
            return true;
        } catch (e) {
            set({ error: 'Network error' });
            return false;
        }
    },

    logout: () => {
        setAuthToken('');
        set({ user: null, isAuthenticated: false, error: null });
    },

    checkAuth: async () => {
        const token = getAuthToken();
        if (!token) {
            set({ isAuthenticated: false, isLoading: false });
            return;
        }
        try {
            const res = await fetch('/api/auth/me', {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const user = await res.json();
                set({ user, isAuthenticated: true, isLoading: false });
            } else {
                setAuthToken('');
                set({ isAuthenticated: false, isLoading: false });
            }
        } catch {
            set({ isAuthenticated: false, isLoading: false });
        }
    },
}));
