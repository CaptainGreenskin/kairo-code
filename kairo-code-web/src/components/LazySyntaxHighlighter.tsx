import { lazy, Suspense } from 'react';
import type { ComponentProps, ComponentType } from 'react';
import type { Prism } from 'react-syntax-highlighter';

type PrismProps = ComponentProps<typeof Prism>;

const PrismWithStyle = lazy(async () => {
    const [mod, styles] = await Promise.all([
        import('react-syntax-highlighter'),
        import('react-syntax-highlighter/dist/esm/styles/prism'),
    ]);
    const Inner = mod.Prism;
    const Wrapped: ComponentType<LazySyntaxHighlighterProps> = (props) => (
        <Inner style={styles.vscDarkPlus as never} {...(props as PrismProps)} />
    );
    return { default: Wrapped };
});

export type LazySyntaxHighlighterProps = Omit<PrismProps, 'style'> & {
    style?: PrismProps['style'];
};

export function LazySyntaxHighlighter(props: LazySyntaxHighlighterProps) {
    return (
        <Suspense
            fallback={
                <pre className="text-xs bg-black/30 p-3 rounded overflow-x-auto">
                    <code>{String(props.children ?? '')}</code>
                </pre>
            }
        >
            <PrismWithStyle {...props} />
        </Suspense>
    );
}
