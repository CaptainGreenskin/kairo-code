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

let refreshTimer: ReturnType<typeof setInterval> | null = null;

function startRefreshTimer() {
    if (refreshTimer) return;
    // Refresh token every 20 hours (token expires at 24h)
    refreshTimer = setInterval(async () => {
        const token = getAuthToken();
        if (!token) return;
        try {
            const res = await fetch('/api/auth/refresh', {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const data = await res.json();
                setAuthToken(data.token);
            }
        } catch { /* ignore */ }
    }, 20 * 60 * 60 * 1000);
}

function stopRefreshTimer() {
    if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
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
            startRefreshTimer();
            return true;
        } catch {
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
            startRefreshTimer();
            return true;
        } catch {
            set({ error: 'Network error' });
            return false;
        }
    },

    logout: () => {
        setAuthToken('');
        stopRefreshTimer();
        set({ user: null, isAuthenticated: false, error: null });
    },

    checkAuth: async () => {
        // Auto-login from URL token (QR code scan with embedded JWT)
        const urlParams = new URLSearchParams(window.location.search);
        const urlToken = urlParams.get('token');
        if (urlToken) {
            setAuthToken(urlToken);
            window.history.replaceState({}, '', window.location.pathname);
        }

        const token = urlToken || getAuthToken();
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
                startRefreshTimer();
            } else {
                setAuthToken('');
                set({ isAuthenticated: false, isLoading: false });
            }
        } catch {
            set({ isAuthenticated: false, isLoading: false });
        }
    },
}));
