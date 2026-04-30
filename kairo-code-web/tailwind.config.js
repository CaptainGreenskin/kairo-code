import type { Config } from 'tailwindcss';

export default {
    content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
    darkMode: 'class',
    theme: {
        extend: {
            colors: {
                surface: {
                    DEFAULT: 'var(--color-surface)',
                    elevated: 'var(--color-surface-elevated)',
                    sunken: 'var(--color-surface-sunken)',
                },
                primary: {
                    DEFAULT: 'var(--color-primary)',
                    hover: 'var(--color-primary-hover)',
                    active: 'var(--color-primary-active)',
                },
                accent: {
                    DEFAULT: 'var(--color-accent)',
                    muted: 'var(--color-accent-muted)',
                },
                danger: { DEFAULT: '#ef4444', muted: '#991b1b' },
                warning: { DEFAULT: '#eab308', muted: '#854d0e' },
                success: { DEFAULT: '#22c55e', muted: '#166534' },
                muted: 'var(--color-muted)',
                border: 'var(--color-border)',
            },
            fontFamily: {
                sans: ['Inter', 'system-ui', 'sans-serif'],
                mono: ["'JetBrains Mono'", "'Fira Code'", 'monospace'],
            },
            animation: {
                'pulse-slow': 'pulse 3s ease-in-out infinite',
                'slide-up': 'slideUp 0.2s ease-out',
                'fade-in': 'fade-in 0.15s ease-out',
            },
            keyframes: {
                'fade-in': {
                    from: { opacity: '0', transform: 'translateY(8px)' },
                    to:   { opacity: '1', transform: 'translateY(0)' },
                },
            },
        },
    },
    plugins: [
        require('@tailwindcss/typography'),
    ],
} satisfies Config;
