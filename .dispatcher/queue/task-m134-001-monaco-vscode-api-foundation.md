---
Status: TODO
Priority: P1
Project: kairo-code
Module: kairo-code-web
Executor: qodercli
Title: M134-P1 — 接入 @codingame/monaco-vscode-api，替换 @monaco-editor/react（基础设施）
---

# M134-P1：换 Monaco 内核为 monaco-vscode-api

## Background

当前 `kairo-code-web` 用 `@monaco-editor/react` 仅嵌入 Monaco 编辑器组件，没有 LSP、Command Palette、主题系统、文件资源管理服务。我们决定走 **chat-first + 渐进式拥抱 VS Code 内核** 路线：保留 React 主壳和 chat 优先 UX，把编辑器底层替换为 `@codingame/monaco-vscode-api`，未来可以按需挂 LSP / 命令面板 / 主题 / 终端服务，不需要 fork 整个 VS Code。

本任务（P1）是**基础设施 PR**：完成底层替换，**不引入任何新功能**，目标是"用户感受不到差异 + 后续 P2/P3/P4 可以增量加 service-override"。

### 全局路线图（仅供参考，不在本 PR 范围）

| 阶段 | 内容 |
|---|---|
| **P1（本 PR）** | 装包 + alias + init services + 重写 `FileEditorPanel` |
| P2 | Command Palette（Cmd+Shift+P）+ VS Code Dark+ 主题 |
| P3 | TS/JS LSP（无需后端） |
| P4 | Java LSP（后端 jdt.ls + WS transport） |

## 相关文档

- Getting Started Guide: https://github.com/codingame/monaco-vscode-api/wiki/Getting-started-guide
- README + service-override 包列表: https://github.com/codingame/monaco-vscode-api
- VS Code Editor API（替换 monaco-editor）: 通过 npm alias `monaco-editor@npm:@codingame/monaco-vscode-editor-api` 注入

## Hard rules

- 仅修改 `kairo-code-web/` 目录。**不要**碰 `kairo-code-server/` / `kairo-code-core/` / `kairo-code-service/`。
- 不引入新功能（不加 command palette / 不加 LSP / 不改 UI 视觉）。只换底层。
- `npm run build` 必须 PASS。
- 浏览器手测：打开任意文件 → 编辑 → ⌘S 保存 → 关闭 tab → 再打开。流程不能有回归。
- 不要删除 `@monaco-editor/react` 包以外的依赖。

---

## Task

### 步骤 1：安装依赖

> **必须先核对版本（重要）**：本 brief 撰写时核对的 latest 是 `31.0.1`。开工前先跑一次：
> ```bash
> npm info @codingame/monaco-vscode-api version
> ```
> 如果返回的版本是 **31.x**，按下面的命令照抄即可。
> 如果是 **更大的 major（32+）**，先去 https://github.com/codingame/monaco-vscode-api/wiki/Getting-started-guide 看一眼 `initialize()` 签名和 worker label 是否还跟 brief 里的代码一致。若不一致，**先把差异列出来跟用户确认再开工**，不要硬改。

在 `kairo-code-web/` 目录执行（**所有版本都 pin 到 31.x，避免 npm 跨 major 自动升级踩坑**）：

```bash
npm uninstall @monaco-editor/react

npm install --save --save-exact \
  @codingame/monaco-vscode-api@31.0.1 \
  monaco-editor@npm:@codingame/monaco-vscode-editor-api@31.0.1 \
  @codingame/monaco-vscode-languages-service-override@31.0.1 \
  @codingame/monaco-vscode-textmate-service-override@31.0.1 \
  @codingame/monaco-vscode-theme-service-override@31.0.1 \
  @codingame/monaco-vscode-model-service-override@31.0.1 \
  @codingame/monaco-vscode-configuration-service-override@31.0.1 \
  @codingame/monaco-vscode-theme-defaults-default-extension@31.0.1 \
  @codingame/monaco-vscode-typescript-basics-default-extension@31.0.1 \
  @codingame/monaco-vscode-javascript-default-extension@31.0.1 \
  @codingame/monaco-vscode-json-default-extension@31.0.1 \
  @codingame/monaco-vscode-markdown-basics-default-extension@31.0.1 \
  @codingame/monaco-vscode-java-default-extension@31.0.1 \
  @codingame/monaco-vscode-xml-default-extension@31.0.1 \
  @codingame/monaco-vscode-html-default-extension@31.0.1 \
  @codingame/monaco-vscode-css-default-extension@31.0.1 \
  @codingame/monaco-vscode-yaml-default-extension@31.0.1

npm install --save-dev --save-exact \
  @codingame/esbuild-import-meta-url-plugin@1.0.4
```

> **注意 1**：`monaco-editor@npm:@codingame/monaco-vscode-editor-api@31.0.1` 是 npm alias 写法 —— `package.json` 里会写成 `"monaco-editor": "npm:@codingame/monaco-vscode-editor-api@31.0.1"`，源码里继续 `import * as monaco from 'monaco-editor'`，但实际包内容来自 codingame fork。
>
> **注意 2**：若某个 service-override 包在 31.0.1 不存在（升级期间偶发），先 `npm info <pkg-name> versions --json` 看一下，挑最接近 31.0.1 的版本写上，**不要混入差距 ≥3 个 major 的版本**。
>
> **注意 3**：`@codingame/esbuild-import-meta-url-plugin` 的版本号是参考值，按 latest 装即可（API 简单稳定）。

### 步骤 2：修改 `vite.config.ts`

文件：`kairo-code-web/vite.config.ts`

在 `defineConfig` 返回的对象顶层加上 `optimizeDeps`、`worker`、调整 `manualChunks`：

```typescript
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import pkg from './package.json';
import importMetaUrlPlugin from '@codingame/esbuild-import-meta-url-plugin';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '');

    return {
        plugins: [react()],

        define: { /* unchanged */ },
        resolve: { /* unchanged */ },
        server: { /* unchanged */ },

        // ---- 新增 ----
        optimizeDeps: {
            esbuildOptions: {
                plugins: [importMetaUrlPlugin],
            },
            // 这些包内含 worker 入口，必须 include 才能在 dev 模式正确预构建
            include: [
                'monaco-editor',
                '@codingame/monaco-vscode-api',
                '@codingame/monaco-vscode-textmate-service-override',
                '@codingame/monaco-vscode-theme-service-override',
                '@codingame/monaco-vscode-languages-service-override',
                '@codingame/monaco-vscode-model-service-override',
                '@codingame/monaco-vscode-configuration-service-override',
            ],
        },

        worker: {
            format: 'es',
        },
        // ---- 新增结束 ----

        build: {
            outDir: 'dist',
            sourcemap: mode === 'development',
            rollupOptions: {
                output: {
                    manualChunks: {
                        'react-vendor': ['react', 'react-dom'],
                        'xterm': ['@xterm/xterm', '@xterm/addon-fit'],
                        'markdown': ['react-markdown'],
                        'syntax-highlighter': ['react-syntax-highlighter'],
                        'virtuoso': ['react-virtuoso'],
                        // ---- 新增 monaco 单独 chunk ----
                        'monaco': ['monaco-editor', '@codingame/monaco-vscode-api'],
                    },
                },
            },
        },

        envPrefix: 'VITE_',

        test: { /* unchanged */ },
    };
});
```

### 步骤 3：新建 `src/monaco/setup.ts`

新文件：`kairo-code-web/src/monaco/setup.ts`

```typescript
/**
 * Initialise monaco-vscode-api services. Must be awaited BEFORE the first
 * `monaco.editor.create(...)` call. See main.tsx for the boot sequence.
 *
 * P1 scope: minimum services needed to keep current FileEditorPanel functional
 * (model + theme + textmate + languages + config). Command Palette / LSP /
 * Terminal services are deliberately deferred to later phases.
 */
import '@codingame/monaco-vscode-theme-defaults-default-extension';
import '@codingame/monaco-vscode-typescript-basics-default-extension';
import '@codingame/monaco-vscode-javascript-default-extension';
import '@codingame/monaco-vscode-json-default-extension';
import '@codingame/monaco-vscode-markdown-basics-default-extension';
import '@codingame/monaco-vscode-java-default-extension';
import '@codingame/monaco-vscode-xml-default-extension';
import '@codingame/monaco-vscode-html-default-extension';
import '@codingame/monaco-vscode-css-default-extension';
import '@codingame/monaco-vscode-yaml-default-extension';

import { initialize } from '@codingame/monaco-vscode-api';
import getModelServiceOverride from '@codingame/monaco-vscode-model-service-override';
import getConfigurationServiceOverride from '@codingame/monaco-vscode-configuration-service-override';
import getThemeServiceOverride from '@codingame/monaco-vscode-theme-service-override';
import getTextmateServiceOverride from '@codingame/monaco-vscode-textmate-service-override';
import getLanguagesServiceOverride from '@codingame/monaco-vscode-languages-service-override';

// Vite-native worker loader: `new Worker(new URL(..., import.meta.url))` is
// the canonical pattern. The `?worker` suffix would also work but URLs are
// more portable across bundlers.
window.MonacoEnvironment = {
    getWorker(_workerId: string, label: string) {
        switch (label) {
            case 'TextEditorWorker':
                return new Worker(
                    new URL('monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url),
                    { type: 'module' },
                );
            case 'TextMateWorker':
                return new Worker(
                    new URL(
                        '@codingame/monaco-vscode-textmate-service-override/worker',
                        import.meta.url,
                    ),
                    { type: 'module' },
                );
            default:
                // Worker labels occasionally change between major versions of monaco-vscode-api.
                // Log loudly so missing syntax highlighting / completions don't look like a silent
                // failure — the label name we received tells you which case to add.
                console.warn(
                    `[monaco] Unknown worker label "${label}". ` +
                    `Syntax highlighting / language features may be degraded. ` +
                    `Check the codingame/monaco-vscode-api wiki for the current worker label list.`,
                );
                throw new Error(`Unknown monaco worker label: ${label}`);
        }
    },
};

let initPromise: Promise<void> | null = null;

export function initMonaco(): Promise<void> {
    if (initPromise) return initPromise;
    initPromise = initialize({
        ...getModelServiceOverride(),
        ...getConfigurationServiceOverride(),
        ...getThemeServiceOverride(),
        ...getTextmateServiceOverride(),
        ...getLanguagesServiceOverride(),
    });
    return initPromise;
}
```

### 步骤 4：修改 `src/main.tsx` —— 启动时初始化 Monaco

文件：`kairo-code-web/src/main.tsx`

```typescript
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/globals.css';
import { initMonaco } from './monaco/setup';

// Theme persistence: restore saved theme or fall back to system preference
const savedTheme = localStorage.getItem('kairo-theme');
if (savedTheme === 'dark' || (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
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
```

### 步骤 5：重写 `src/components/FileEditorPanel.tsx`

文件：`kairo-code-web/src/components/FileEditorPanel.tsx`

把 `import Editor, { type OnMount } from '@monaco-editor/react'` 整个删掉，换成原生 monaco 控制。**只改 editor 部分（步骤 5.1）**，header / loading / binary / oversize / markdown preview 这些分支不要动。

#### 5.1 替换 import 和 ref 类型

```typescript
// 删掉：
import Editor, { type OnMount } from '@monaco-editor/react';

// 新增：
import * as monaco from 'monaco-editor';
```

#### 5.2 替换 editor mount 逻辑

把组件里的 `editorRef`/`monacoRef` 部分（约第 110-134 行）换成：

```typescript
const editorContainerRef = useRef<HTMLDivElement | null>(null);
const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
const modelRef = useRef<monaco.editor.ITextModel | null>(null);

// Mount editor when container is ready and content has loaded.
useEffect(() => {
    if (loading || binary || oversize) return;
    if (isMarkdown && viewMode === 'preview') return;
    if (!editorContainerRef.current) return;
    if (editorRef.current) return;  // already mounted

    const model = monaco.editor.createModel(content, language);
    modelRef.current = model;

    const editor = monaco.editor.create(editorContainerRef.current, {
        model,
        theme: 'vs-dark',
        minimap: { enabled: false },
        fontSize: 13,
        lineNumbers: 'on',
        scrollBeyondLastLine: false,
        automaticLayout: true,
    });
    editorRef.current = editor;

    const sub = model.onDidChangeContent(() => {
        setContent(model.getValue());
    });

    // ⌘S / Ctrl+S → save
    editor.addCommand(
        monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS,
        () => handleSaveRef.current(),
    );

    if (gotoLine != null) {
        editor.revealLineInCenter(gotoLine);
        editor.setPosition({ lineNumber: gotoLine, column: 1 });
    }

    return () => {
        sub.dispose();
        editor.dispose();
        model.dispose();
        editorRef.current = null;
        modelRef.current = null;
    };
    // Only depend on loading/binary/oversize/viewMode/language transitions that
    // require a remount. `content` changes are pushed via model.setValue below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
}, [loading, binary, oversize, isMarkdown, viewMode, language]);

// External content updates (initial load, gotoLine refresh) → push into model.
useEffect(() => {
    const model = modelRef.current;
    if (!model) return;
    if (model.getValue() !== content) {
        model.setValue(content);
    }
}, [content]);
```

#### 5.3 替换 `gotoLine` useEffect

旧代码（约第 117-134 行）保留，但把内部用 `monaco.Range` 的地方改成直接用顶部 import 的 `monaco`，
**并把已废弃的 `editor.deltaDecorations(...)` 换成 `editor.createDecorationsCollection(...)`**
（monaco-vscode-api 31.x 起 deltaDecorations 会触发 deprecation warning）：

```typescript
useEffect(() => {
    if (gotoLine == null || !editorRef.current || loading) return;
    const editor = editorRef.current;
    editor.revealLineInCenter(gotoLine);
    editor.setPosition({ lineNumber: gotoLine, column: 1 });
    editor.focus();
    const collection = editor.createDecorationsCollection([{
        range: new monaco.Range(gotoLine, 1, gotoLine, 1),
        options: {
            isWholeLine: true,
            className: 'bg-[var(--color-primary)]/15',
            marginClassName: 'bg-[var(--color-primary)]',
        },
    }]);
    const t = setTimeout(() => collection.clear(), 1500);
    return () => {
        clearTimeout(t);
        collection.clear();
    };
}, [gotoLine, loading]);
```

#### 5.4 替换 JSX 渲染部分

把约第 226-253 行的 `<Editor ... />` 整段换成：

```jsx
<div className="flex-1">
    <div
        ref={editorContainerRef}
        className="w-full h-full"
        data-testid="monaco-editor-container"
    />
</div>
```

#### 5.5 删除旧的 `Editor`/`OnMount` 相关类型别名

把原文件里的：
```typescript
type Editor = Parameters<OnMount>[0];
type Monaco = Parameters<OnMount>[1];
const editorRef = useRef<Editor | null>(null);
const monacoRef = useRef<Monaco | null>(null);
```
全部删除（已被 5.2 的新 ref 取代）。

### 步骤 6：扫描其他 `@monaco-editor/react` 使用点

```bash
grep -r "@monaco-editor/react" kairo-code-web/src/ kairo-code-web/*.ts kairo-code-web/*.tsx 2>/dev/null
```

预期只有 `FileEditorPanel.tsx` 一处。如果还有其他文件（如 `MemoryEditorPanel.tsx`），按同样模式重构（替换 `<Editor>` 组件 → `monaco.editor.create` + container div）。

如果遇到 `DiffEditor`，对应换成 `monaco.editor.createDiffEditor`。

### 步骤 7：构建 + 验证

```bash
cd kairo-code-web
npm run build
```

构建必须成功。bundle 体积预期会从 ~5MB 涨到 ~8MB（monaco-vscode-api ≈ +3MB），这是正常的，**不要**为了减体积去删 service-override 包。

### 步骤 8：浏览器手测

启动 dev server：
```bash
cd kairo-code-web
npm run dev
```

测试清单（每条都必须通过）：

- [ ] 页面加载时看到 "Loading editor…" splash，约 1-3s 后切到正常 UI
- [ ] 浏览器 console 无红色 error（warning 可接受）
- [ ] 左侧文件树点开任意 `.ts` 文件 → 编辑器打开 → 看到正确语法高亮
- [ ] 同上，打开 `.java` 文件 → Java 关键字高亮正确
- [ ] 同上，打开 `.md` 文件 → 默认进入 preview 视图，点 ✏️ 切到 edit 视图
- [ ] 编辑文件后顶栏出现 `●` dirty 标记
- [ ] 按 ⌘S（Mac）/ Ctrl+S（Linux/Win）→ 保存成功，`●` 消失，后端文件落盘
- [ ] 点击 "Save" 按钮 → 同上效果
- [ ] 关闭 tab 后再打开同一文件 → 内容是最新保存版本
- [ ] 从 chat 里点 `file.ts:42` 这种链接 → 跳到 42 行，高亮 1.5s 后消失
- [ ] 主题：vs-dark 风格不变（背景深色，关键字蓝色）
- [ ] 同时打开 3+ 个 tab，切换流畅，无内存泄漏（DevTools Performance 录 1min 看不到无限增长）

### 步骤 9：commit

```bash
cd /Users/liulihan/IdeaProjects/sre/claude/kairo-code
git add kairo-code-web/package.json kairo-code-web/package-lock.json \
        kairo-code-web/vite.config.ts \
        kairo-code-web/src/main.tsx \
        kairo-code-web/src/monaco/setup.ts \
        kairo-code-web/src/components/FileEditorPanel.tsx
# 如果步骤 6 涉及其他文件，一起 add 进来

git commit -m "feat(web): replace @monaco-editor/react with monaco-vscode-api (P1 foundation)

P1 of M134 — chat-first + 渐进式 VS Code 内核. This PR only swaps the editor
backend; no new features (Command Palette/LSP/themes come in P2-P4).

- Install @codingame/monaco-vscode-api stack with model/config/theme/textmate/
  languages service overrides
- npm alias 'monaco-editor' → '@codingame/monaco-vscode-editor-api' so existing
  'import * as monaco from monaco-editor' calls keep working
- Init services before App mount with a Loading splash
- Rewrite FileEditorPanel to use native monaco.editor.create instead of the React
  wrapper (manual model/editor lifecycle, ⌘S binding, gotoLine decoration)
- Configure vite for import.meta.url worker loading + monaco chunk split"
```

---

## Out of scope (do NOT do)

- 不要在本 PR 加 Command Palette / 快捷键面板 / 主题切换 UI
- 不要接 LSP（无论 TS、Python 还是 Java）
- 不要改 chat panel / agent / WebSocket 任何代码
- 不要改 `kairo-code-server/` / `kairo-code-core/` / `kairo-code-service/`
- 不要重构 `EditorArea.tsx` 的 tab 逻辑（继续用 React state，**不**切到 monaco editor group）
- 不要去掉现有的 markdown preview 分支
- 看到 bundle 警告 "Some chunks are larger than 500 kB" 不要去 chunk-split 调优，是正常的

---

## Acceptance criteria

1. `npm run build` PASS
2. 步骤 8 手测清单 12 项全过
3. `grep "@monaco-editor/react" kairo-code-web/` 应该返回 0 个结果
4. `package.json` 里：
   - `@monaco-editor/react` 已删除
   - `monaco-editor` 字段值是 `"npm:@codingame/monaco-vscode-editor-api@..."`
   - `@codingame/monaco-vscode-api` 和 ≥5 个 service-override 已加入
5. Git diff 只触及上述 5-6 个文件 + `package-lock.json`

---

## 风险 & 回滚

风险点：
- `monaco-vscode-api` 版本之间 API 变动较大。如果遇到 `initialize is not a function` 之类错，先 `npm view @codingame/monaco-vscode-api versions --json` 看是不是装错了 major（本 brief 全部 pin 到 **31.0.1**）。
- **`Cannot find module 'vscode'` 构建报错**：某些 service-override 包内部 `import * as vscode from 'vscode'`，需要在 `vite.config.ts` 的 `resolve.alias` 加：
  ```typescript
  resolve: {
      alias: {
          // ... existing aliases
          'vscode': '@codingame/monaco-vscode-api/vscode',
      },
  }
  ```
  如果 `npm run build` 没报这个错就**不要加**（多余的 alias 会拖慢预构建）。只在遇到错误时再补。
- **Worker 加载 404 / "Unknown worker label"**：步骤 3 的 `getWorker` switch 已经加了 warn 日志，如果浏览器 console 看到 `[monaco] Unknown worker label "xxx"`，把 `xxx` 加成新 case 并指向对应包的 `/worker` 入口（先在 `node_modules/@codingame/monaco-vscode-*-service-override/` 下 grep 找 worker 文件）。
- `import.meta.url` 在 jest/vitest 环境可能报错 —— 如果 `npm test` 跑不动，给 `vitest.config.ts` 或 `src/test/setup.ts` 加上 `vi.mock('@codingame/monaco-vscode-api', () => ({ initialize: vi.fn() }))`。

回滚：直接 `git revert` 该 commit 即可，没有任何后端/数据迁移。

---

**Done when**：commit 推上去 + 手测清单全过。
