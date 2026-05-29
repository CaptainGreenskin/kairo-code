import { useEffect, useState } from 'react';

export function useMonacoTheme(): string {
    const [theme, setTheme] = useState(() =>
        document.documentElement.classList.contains('dark') ? 'vs-dark' : 'vs',
    );
    useEffect(() => {
        const observer = new MutationObserver(() => {
            setTheme(document.documentElement.classList.contains('dark') ? 'vs-dark' : 'vs');
        });
        observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
        return () => observer.disconnect();
    }, []);
    return theme;
}
