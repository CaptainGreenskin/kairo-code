import { lazy, Suspense } from 'react';
import type { ComponentProps } from 'react';

const Markdown = lazy(() => import('react-markdown'));

type MarkdownProps = ComponentProps<typeof Markdown>;

export function LazyMarkdown(props: MarkdownProps) {
    return (
        <Suspense
            fallback={
                <pre className="text-xs whitespace-pre-wrap opacity-70">
                    {String(props.children ?? '')}
                </pre>
            }
        >
            <Markdown {...props} />
        </Suspense>
    );
}
