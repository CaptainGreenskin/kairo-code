import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/globals.css';
import { initMonaco } from './monaco/setup';
import { installAuthInterceptor } from './api/auth';
import { useAuthStore } from './store/authStore';

// Inject the server auth token into all /api requests before anything fetches.
installAuthInterceptor();

// Validate stored token on startup (non-blocking — authStore.isLoading gates the UI).
useAuthStore.getState().checkAuth();

// Theme persistence: restore saved theme or default to dark (Cursor-style)
const savedTheme = localStorage.getItem('kairo-theme');
if (savedTheme !== 'light') {
    document.documentElement.classList.add('dark');
}

// Init Monaco services BEFORE rendering. Splash is rendered immediately so the
// user sees feedback while ~2-3s of worker bootstrapping happens on first load.
const root = ReactDOM.createRoot(document.getElementById('root')!);
root.render(
    <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        height: '100vh', color: '#888', fontSize: 13, fontFamily: 'system-ui',
    }}>
        Loading editor…
    </div>,
);

initMonaco().then(() => {
    root.render(
        <React.StrictMode>
            <App />
        </React.StrictMode>,
    );
}).catch((err) => {
    console.error('Monaco init failed:', err);
    root.render(
        <div style={{ padding: 20, color: 'crimson' }}>
            Editor failed to load: {String(err?.message ?? err)}
        </div>,
    );
});
