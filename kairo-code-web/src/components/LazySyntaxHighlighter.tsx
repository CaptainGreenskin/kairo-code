import { lazy, Suspense } from 'react';
import type { ComponentProps, ComponentType } from 'react';
import type { PrismLight } from 'react-syntax-highlighter';

type PrismLightProps = ComponentProps<typeof PrismLight>;

const PrismLightWithStyle = lazy(async () => {
    const [{ PrismLight }, styles] = await Promise.all([
        import('react-syntax-highlighter'),
        import('react-syntax-highlighter/dist/esm/styles/prism'),
    ]);

    // 注册 ChatMessage 实际使用的语言
    const [
        { default: tsx },
        { default: typescript },
        { default: javascript },
        { default: jsx },
        { default: python },
        { default: java },
        { default: bash },
        { default: json },
        { default: yaml },
        { default: css },
        { default: sql },
        { default: markdown },
        { default: markup },
        { default: go },
        { default: rust },
        { default: cpp },
    ] = await Promise.all([
        import('react-syntax-highlighter/dist/esm/languages/prism/tsx'),
        import('react-syntax-highlighter/dist/esm/languages/prism/typescript'),
        import('react-syntax-highlighter/dist/esm/languages/prism/javascript'),
        import('react-syntax-highlighter/dist/esm/languages/prism/jsx'),
        import('react-syntax-highlighter/dist/esm/languages/prism/python'),
        import('react-syntax-highlighter/dist/esm/languages/prism/java'),
        import('react-syntax-highlighter/dist/esm/languages/prism/bash'),
        import('react-syntax-highlighter/dist/esm/languages/prism/json'),
        import('react-syntax-highlighter/dist/esm/languages/prism/yaml'),
        import('react-syntax-highlighter/dist/esm/languages/prism/css'),
        import('react-syntax-highlighter/dist/esm/languages/prism/sql'),
        import('react-syntax-highlighter/dist/esm/languages/prism/markdown'),
        import('react-syntax-highlighter/dist/esm/languages/prism/markup'),
        import('react-syntax-highlighter/dist/esm/languages/prism/go'),
        import('react-syntax-highlighter/dist/esm/languages/prism/rust'),
        import('react-syntax-highlighter/dist/esm/languages/prism/cpp'),
    ]);

    PrismLight.registerLanguage('tsx', tsx);
    PrismLight.registerLanguage('typescript', typescript);
    PrismLight.registerLanguage('ts', typescript);
    PrismLight.registerLanguage('javascript', javascript);
    PrismLight.registerLanguage('js', javascript);
    PrismLight.registerLanguage('jsx', jsx);
    PrismLight.registerLanguage('python', python);
    PrismLight.registerLanguage('py', python);
    PrismLight.registerLanguage('java', java);
    PrismLight.registerLanguage('bash', bash);
    PrismLight.registerLanguage('sh', bash);
    PrismLight.registerLanguage('shell', bash);
    PrismLight.registerLanguage('json', json);
    PrismLight.registerLanguage('yaml', yaml);
    PrismLight.registerLanguage('yml', yaml);
    PrismLight.registerLanguage('css', css);
    PrismLight.registerLanguage('sql', sql);
    PrismLight.registerLanguage('markdown', markdown);
    PrismLight.registerLanguage('xml', markup);
    PrismLight.registerLanguage('go', go);
    PrismLight.registerLanguage('rust', rust);
    PrismLight.registerLanguage('cpp', cpp);

    const Wrapped: ComponentType<LazySyntaxHighlighterProps> = (props) => (
        <PrismLight style={styles.vscDarkPlus as never} {...(props as PrismLightProps)} />
    );
    return { default: Wrapped };
});

export type LazySyntaxHighlighterProps = Omit<PrismLightProps, 'style'> & {
    style?: PrismLightProps['style'];
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
            <PrismLightWithStyle {...props} />
        </Suspense>
    );
}
