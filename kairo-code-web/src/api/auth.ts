/**
 * Client-side auth for the kairo-code server.
 *
 * The server (ApiAuthFilter) gates every /api and /ws request behind a shared
 * bearer token. The browser stores it in localStorage; we inject it two ways:
 *   - REST: a global fetch interceptor adds `Authorization: Bearer <token>` to
 *     same-origin /api calls (so individual call sites don't each need wiring).
 *   - WebSocket: native WebSocket can't set headers, so the token rides as a
 *     `?token=` query param (see appendTokenToWsUrl).
 *
 * When no token is stored (e.g. loopback dev where the server allows loopback),
 * nothing is added and requests work as before.
 */

const TOKEN_KEY = 'kairo.authToken';

export function getAuthToken(): string {
    try {
        return localStorage.getItem(TOKEN_KEY) ?? '';
    } catch {
        return '';
    }
}

export function setAuthToken(token: string): void {
    try {
        if (token) localStorage.setItem(TOKEN_KEY, token);
        else localStorage.removeItem(TOKEN_KEY);
    } catch {
        /* ignore storage errors */
    }
}

/** Append the stored token as a query param for WebSocket URLs. */
export function appendTokenToWsUrl(url: string): string {
    const token = getAuthToken();
    if (!token) return url;
    const sep = url.includes('?') ? '&' : '?';
    return `${url}${sep}token=${encodeURIComponent(token)}`;
}

function isApiUrl(url: string): boolean {
    if (url.startsWith('/api')) return true;
    try {
        const u = new URL(url, window.location.origin);
        return u.origin === window.location.origin && u.pathname.startsWith('/api');
    } catch {
        return false;
    }
}

let installed = false;

/** Install the global fetch interceptor once, at app startup. */
export function installAuthInterceptor(): void {
    if (installed) return;
    installed = true;
    const original = window.fetch.bind(window);
    window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const token = getAuthToken();
        if (token && typeof input === 'string' && isApiUrl(input)) {
            const headers = new Headers(init?.headers || {});
            if (!headers.has('Authorization')) {
                headers.set('Authorization', `Bearer ${token}`);
            }
            return original(input, { ...init, headers });
        }
        if (token && input instanceof URL && isApiUrl(input.toString())) {
            const headers = new Headers(init?.headers || {});
            if (!headers.has('Authorization')) {
                headers.set('Authorization', `Bearer ${token}`);
            }
            return original(input, { ...init, headers });
        }
        return original(input, init);
    };
}
