import { useState, useEffect } from 'react';

type Breakpoint = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

const BREAKPOINTS = {
    xs: 0,
    sm: 640,
    md: 768,
    lg: 1024,
    xl: 1280,
};

export function useBreakpoint(): Breakpoint {
    const getBreakpoint = (): Breakpoint => {
        const w = window.innerWidth;
        if (w < BREAKPOINTS.sm) return 'xs';
        if (w < BREAKPOINTS.md) return 'sm';
        if (w < BREAKPOINTS.lg) return 'md';
        if (w < BREAKPOINTS.xl) return 'lg';
        return 'xl';
    };

    const [bp, setBp] = useState<Breakpoint>(getBreakpoint);

    useEffect(() => {
        const handler = () => setBp(getBreakpoint());
        window.addEventListener('resize', handler);
        return () => window.removeEventListener('resize', handler);
    }, []);

    return bp;
}
