import { lazy, Suspense } from 'react';
import type { ComponentProps } from 'react';
import remarkGfm from 'remark-gfm';

const Markdown = lazy(() => import('react-markdown'));

type MarkdownProps = ComponentProps<typeof Markdown>;

const DEFAULT_REMARK_PLUGINS = [remarkGfm];

/**
 * Wrapper around react-markdown. GFM is enabled by default so tables, strikethrough,
 * task lists, and autolinks render correctly. Callers can extend `remarkPlugins` —
 * we always prepend GFM.
 */
export function LazyMarkdown(props: MarkdownProps) {
    const merged: MarkdownProps = {
        ...props,
        remarkPlugins: [...DEFAULT_REMARK_PLUGINS, ...(props.remarkPlugins ?? [])],
    };
    return (
        <Suspense
            fallback={
                <pre className="text-xs whitespace-pre-wrap opacity-70">
                    {String(props.children ?? '')}
                </pre>
            }
        >
            <Markdown {...merged} />
        </Suspense>
    );
}
