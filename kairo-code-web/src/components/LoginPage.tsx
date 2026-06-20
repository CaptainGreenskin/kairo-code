import React, { useState, useEffect } from 'react';
import { useAuthStore } from '../store/authStore';

export function LoginPage() {
    const [tab, setTab] = useState<'login' | 'register'>('login');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [inviteCode, setInviteCode] = useState('');
    const [requiresInvite, setRequiresInvite] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    const { login, register, error } = useAuthStore();

    useEffect(() => {
        fetch('/api/auth/status')
            .then(r => r.json())
            .then(data => {
                setRequiresInvite(data.requiresInviteCode ?? false);
                if (!data.hasUsers) setTab('register');
            })
            .catch(() => {});
    }, []);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (submitting) return;

        if (tab === 'register' && password !== confirmPassword) {
            useAuthStore.setState({ error: 'Passwords do not match' });
            return;
        }

        setSubmitting(true);
        if (tab === 'login') {
            await login(username, password);
        } else {
            await register(username, password, inviteCode);
        }
        setSubmitting(false);
    };

    return (
        <div className="h-screen w-screen flex items-center justify-center"
             style={{ background: 'var(--bg-primary)', color: 'var(--text-primary)' }}>
            <div className="w-full max-w-sm p-8 rounded-lg"
                 style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
                <h1 className="text-xl font-semibold text-center mb-6">Kairo Code</h1>

                <div className="flex mb-6 rounded-md overflow-hidden"
                     style={{ border: '1px solid var(--border-color)' }}>
                    <button
                        className="flex-1 py-2 text-sm font-medium transition-colors"
                        style={{
                            background: tab === 'login' ? 'var(--accent-color)' : 'transparent',
                            color: tab === 'login' ? '#fff' : 'var(--text-secondary)',
                        }}
                        onClick={() => setTab('login')}
                    >
                        Login
                    </button>
                    <button
                        className="flex-1 py-2 text-sm font-medium transition-colors"
                        style={{
                            background: tab === 'register' ? 'var(--accent-color)' : 'transparent',
                            color: tab === 'register' ? '#fff' : 'var(--text-secondary)',
                        }}
                        onClick={() => setTab('register')}
                    >
                        Register
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="space-y-4">
                    <input
                        type="text"
                        placeholder="Username"
                        value={username}
                        onChange={e => setUsername(e.target.value)}
                        autoComplete="username"
                        className="w-full px-3 py-2 rounded-md text-sm outline-none"
                        style={{
                            background: 'var(--bg-primary)',
                            border: '1px solid var(--border-color)',
                            color: 'var(--text-primary)',
                        }}
                    />
                    <input
                        type="password"
                        placeholder="Password"
                        value={password}
                        onChange={e => setPassword(e.target.value)}
                        autoComplete={tab === 'login' ? 'current-password' : 'new-password'}
                        className="w-full px-3 py-2 rounded-md text-sm outline-none"
                        style={{
                            background: 'var(--bg-primary)',
                            border: '1px solid var(--border-color)',
                            color: 'var(--text-primary)',
                        }}
                    />
                    {tab === 'register' && (
                        <input
                            type="password"
                            placeholder="Confirm Password"
                            value={confirmPassword}
                            onChange={e => setConfirmPassword(e.target.value)}
                            autoComplete="new-password"
                            className="w-full px-3 py-2 rounded-md text-sm outline-none"
                            style={{
                                background: 'var(--bg-primary)',
                                border: '1px solid var(--border-color)',
                                color: 'var(--text-primary)',
                            }}
                        />
                    )}
                    {tab === 'register' && requiresInvite && (
                        <input
                            type="text"
                            placeholder="Invite Code"
                            value={inviteCode}
                            onChange={e => setInviteCode(e.target.value)}
                            className="w-full px-3 py-2 rounded-md text-sm outline-none"
                            style={{
                                background: 'var(--bg-primary)',
                                border: '1px solid var(--border-color)',
                                color: 'var(--text-primary)',
                            }}
                        />
                    )}

                    {error && (
                        <div className="text-sm px-3 py-2 rounded-md"
                             style={{ background: 'rgba(220,38,38,0.1)', color: '#ef4444' }}>
                            {error}
                        </div>
                    )}

                    <button
                        type="submit"
                        disabled={submitting || !username || !password}
                        className="w-full py-2 rounded-md text-sm font-medium transition-opacity"
                        style={{
                            background: 'var(--accent-color)',
                            color: '#fff',
                            opacity: submitting || !username || !password ? 0.5 : 1,
                        }}
                    >
                        {submitting ? 'Please wait...' : (tab === 'login' ? 'Sign In' : 'Create Account')}
                    </button>
                </form>
            </div>
        </div>
    );
}
