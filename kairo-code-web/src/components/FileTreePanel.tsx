import { useState, useCallback, useEffect, useRef } from 'react';
import {
    Folder,
    FolderOpen,
    File,
    FileCode,
    ChevronRight,
    ChevronDown,
    RefreshCw,
    Loader2,
    AtSign,
    FilePlus,
    FolderPlus,
    Pencil,
    Trash2,
} from 'lucide-react';
import { listFiles, getFileContent, deleteFile, renameFile, createFile, createDir } from '@api/config';
import type { FileEntry } from '@/types/agent';

interface FileTreePanelProps {
    isOpen: boolean;
    onToggle: () => void;
    onInsertFile: (path: string, content: string, language: string) => void;
    onMentionFile?: (path: string) => void;
    onOpenInEditor?: (path: string) => void;
    width?: number;
    rootKey?: string;
    workspaceId?: string;
    /** When true, render without the outer toggle column / fixed-width chrome. The container is responsible for sizing. */
    embedded?: boolean;
}

type TreeNode = {
    entry: FileEntry;
    children?: TreeNode[];
    loading?: boolean;
    expanded?: boolean;
};

interface ContextMenu {
    x: number;
    y: number;
    entry: FileEntry;
}

const CODE_EXTENSIONS = new Set([
    'java', 'kt', 'kts', 'ts', 'tsx', 'js', 'jsx', 'py', 'go', 'rs',
    'rb', 'cs', 'cpp', 'cc', 'cxx', 'c', 'h', 'scala', 'groovy',
    'yaml', 'yml', 'json', 'xml', 'html', 'htm', 'css', 'scss',
    'sh', 'bash', 'sql', 'toml', 'properties', 'gradle', 'md',
]);

function isCodeFile(name: string): boolean {
    const dot = name.lastIndexOf('.');
    if (dot < 0) return false;
    return CODE_EXTENSIONS.has(name.substring(dot + 1).toLowerCase());
}

function FileIcon({ name, expanded }: { name: string; expanded?: boolean }) {
    if (expanded !== undefined) {
        return expanded
            ? <FolderOpen size={14} className="text-[var(--color-info)] shrink-0" />
            : <Folder size={14} className="text-[var(--color-info)] shrink-0" />;
    }
    return isCodeFile(name)
        ? <FileCode size={14} className="text-[var(--text-secondary)] shrink-0" />
        : <File size={14} className="text-[var(--text-muted)] shrink-0" />;
}

/**
 * Walk down a chain of single-dir-child directories. Returns [head, ..., tail]
 * where each but the last has loaded children equal to a single directory.
 * VS Code-style "compact folders" — collapses runs like `src → main → java → io`
 * into a single row (`src / main / java / io`) so tall trees stay readable.
 */
function buildChain(node: TreeNode): TreeNode[] {
    if (!node.entry.isDir) return [node];
    const chain: TreeNode[] = [node];
    let cur = node;
    while (
        cur.children !== undefined &&
        cur.children.length === 1 &&
        cur.children[0].entry.isDir
    ) {
        cur = cur.children[0];
        chain.push(cur);
    }
    return chain;
}

function TreeNodeItem({
    node,
    depth,
    onExpand,
    onSelect,
    onMentionFile,
    onContextMenu,
    selectedPath,
}: {
    node: TreeNode;
    depth: number;
    onExpand: (path: string) => void;
    onSelect: (entry: FileEntry) => void;
    onMentionFile?: (path: string) => void;
    onContextMenu: (e: React.MouseEvent, entry: FileEntry) => void;
    selectedPath?: string;
}) {
    const isDir = node.entry.isDir;
    const chain = buildChain(node);
    const isCompact = chain.length > 1;
    // Tail of the chain is the directory whose children we actually render and
    // whose expand state drives the chevron. For non-chain rows tail === node.
    const tail = chain[chain.length - 1];
    const expanded = isDir ? !!tail.expanded : false;
    const targetEntry = tail.entry;
    const targetChildren = tail.children;
    const targetLoading = tail.loading;
    const isSelected = targetEntry.path === selectedPath;

    return (
        <div>
            <div
                className={`group w-full flex items-center gap-1.5 py-1 text-sm text-left transition-colors truncate cursor-pointer ${
                    isSelected ? 'bg-[var(--color-primary)]/15' : 'hover:bg-[var(--bg-hover)]'
                }`}
                style={{ paddingLeft: `${depth * 12 + 8}px` }}
                onContextMenu={(e) => onContextMenu(e, targetEntry)}
            >
                <button
                    className={`flex-1 flex items-center gap-1.5 min-w-0 text-left ${
                        !isDir ? 'text-[var(--text-secondary)]' : 'text-[var(--text-primary)]'
                    }`}
                    onClick={() => {
                        if (isDir) onExpand(targetEntry.path);
                        else onSelect(node.entry);
                    }}
                    title={targetEntry.path}
                >
                    {isDir && (
                        <span className="shrink-0 text-[var(--text-muted)]">
                            {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        </span>
                    )}
                    {!isDir && <span className="w-3 shrink-0" />}
                    <FileIcon name={targetEntry.name} expanded={isDir ? expanded : undefined} />
                    {isCompact ? (
                        <span className="truncate">
                            {chain.map((n, i) => (
                                <span key={n.entry.path}>
                                    {i > 0 && (
                                        <span className="text-[var(--text-muted)] mx-1">/</span>
                                    )}
                                    {n.entry.name}
                                </span>
                            ))}
                        </span>
                    ) : (
                        <span className="truncate">{node.entry.name}</span>
                    )}
                </button>
                {!isDir && onMentionFile && (
                    <button
                        onClick={(e) => { e.stopPropagation(); onMentionFile(node.entry.path); }}
                        className="shrink-0 opacity-0 group-hover:opacity-100 p-0.5 text-[var(--text-muted)] hover:text-[var(--accent)] transition-opacity"
                        title={`Mention @${node.entry.name}`}
                    >
                        <AtSign size={11} />
                    </button>
                )}
            </div>
            {isDir && expanded && (
                <div>
                    {targetLoading && (
                        <div
                            className="flex items-center gap-1.5 py-1 text-xs text-[var(--text-muted)]"
                            style={{ paddingLeft: `${(depth + 1) * 12 + 8}px` }}
                        >
                            <Loader2 size={12} className="animate-spin" />
                            Loading...
                        </div>
                    )}
                    {!targetLoading && targetChildren?.map((child) => (
                        <TreeNodeItem
                            key={child.entry.path}
                            node={child}
                            depth={depth + 1}
                            onExpand={onExpand}
                            onSelect={onSelect}
                            onMentionFile={onMentionFile}
                            onContextMenu={onContextMenu}
                            selectedPath={selectedPath}
                        />
                    ))}
                    {!targetLoading && targetChildren && targetChildren.length === 0 && (
                        <div
                            className="py-1 text-xs text-[var(--text-muted)] italic"
                            style={{ paddingLeft: `${(depth + 1) * 12 + 8}px` }}
                        >
                            (empty)
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

function ContextMenuPopup({
    menu,
    onClose,
    onAction,
}: {
    menu: ContextMenu;
    onClose: () => void;
    onAction: (action: string, entry: FileEntry) => void;
}) {
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) onClose();
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [onClose]);

    const { entry } = menu;
    const isDir = entry.isDir;

    // Clamp position so menu stays in viewport
    const style: React.CSSProperties = {
        position: 'fixed',
        top: menu.y,
        left: menu.x,
        zIndex: 9999,
    };

    const Item = ({ icon, label, action, danger }: { icon: React.ReactNode; label: string; action: string; danger?: boolean }) => (
        <button
            className={`w-full flex items-center gap-2 px-3 py-1.5 text-xs text-left transition-colors hover:bg-[var(--bg-hover)] ${
                danger ? 'text-[var(--color-danger)]' : 'text-[var(--text-primary)]'
            }`}
            onClick={() => { onAction(action, entry); onClose(); }}
        >
            {icon}
            {label}
        </button>
    );

    return (
        <div
            ref={ref}
            style={style}
            className="min-w-40 rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] shadow-xl py-1 overflow-hidden"
        >
            {!isDir && (
                <>
                    <Item icon={<FileCode size={12} />} label="Open in Editor" action="open" />
                    <Item icon={<AtSign size={12} />} label="Insert to Chat" action="insert" />
                    <div className="my-1 border-t border-[var(--border)]" />
                </>
            )}
            {isDir && (
                <>
                    <Item icon={<FilePlus size={12} />} label="New File" action="new-file" />
                    <Item icon={<FolderPlus size={12} />} label="New Folder" action="new-dir" />
                    <div className="my-1 border-t border-[var(--border)]" />
                </>
            )}
            <Item icon={<Pencil size={12} />} label="Rename" action="rename" />
            <Item icon={<Trash2 size={12} />} label="Delete" action="delete" danger />
        </div>
    );
}

export function FileTreePanel({ isOpen, onToggle, onInsertFile, onMentionFile, onOpenInEditor, width = 240, rootKey, workspaceId, embedded = false }: FileTreePanelProps) {
    const [rootNodes, setRootNodes] = useState<TreeNode[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [selectedPath, setSelectedPath] = useState<string | undefined>(undefined);
    const [contextMenu, setContextMenu] = useState<ContextMenu | null>(null);

    const loadRoot = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const entries = await listFiles(undefined, workspaceId);
            setRootNodes(entries.map((e) => ({ entry: e })));
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load files');
            setRootNodes([]);
        } finally {
            setLoading(false);
        }
    }, [workspaceId]);

    useEffect(() => {
        if (isOpen && rootNodes.length === 0 && !loading) loadRoot();
    }, [isOpen]); // eslint-disable-line react-hooks/exhaustive-deps

    // Reload when workingDir changes (e.g. after Settings save) — without this,
    // FileTreePanel keeps showing the previous workspace's contents.
    useEffect(() => {
        if (rootKey === undefined) return;
        loadRoot();
    }, [rootKey]); // eslint-disable-line react-hooks/exhaustive-deps

    const updateNodeChildren = useCallback((nodes: TreeNode[], path: string, children: TreeNode[], opts?: { loading?: boolean }): TreeNode[] =>
        nodes.map((node) => {
            if (node.entry.path === path) {
                return { ...node, loading: opts?.loading ?? false, children };
            }
            if (node.children) {
                return { ...node, children: updateNodeChildren(node.children, path, children, opts) };
            }
            return node;
        }), []);

    /**
     * Recursively load children, cascading into single-dir-children so the entire
     * compact chain materialises in one round-trip set. The deepest dir whose
     * children break the chain (>1 entry, or a file) is marked expanded so the
     * user immediately sees the contents under the compact row.
     */
    const loadCascade = useCallback(async (path: string): Promise<TreeNode[]> => {
        let entries: FileEntry[];
        try {
            entries = await listFiles(path, workspaceId);
        } catch {
            return [];
        }
        const children: TreeNode[] = entries.map((e) => ({ entry: e }));
        if (children.length === 1 && children[0].entry.isDir) {
            const grandchildren = await loadCascade(children[0].entry.path);
            const continuesChain =
                grandchildren.length === 1 && grandchildren[0].entry.isDir;
            children[0] = {
                ...children[0],
                children: grandchildren,
                // Auto-open the chain tail so clicking the head reveals contents.
                expanded: !continuesChain,
            };
        }
        return children;
    }, [workspaceId]);

    const handleExpand = useCallback(async (path: string) => {
        const findNode = (nodes: TreeNode[], target: string): TreeNode | null => {
            for (const n of nodes) {
                if (n.entry.path === target) return n;
                if (n.children) { const f = findNode(n.children, target); if (f) return f; }
            }
            return null;
        };

        setRootNodes((prev) => {
            const toggle = (nodes: TreeNode[]): TreeNode[] =>
                nodes.map((node) => {
                    if (node.entry.path === path) {
                        if (node.children !== undefined) return { ...node, expanded: !node.expanded };
                        return { ...node, loading: true, expanded: true };
                    }
                    if (node.children) return { ...node, children: toggle(node.children) };
                    return node;
                });
            return toggle(prev);
        });

        const current = findNode(rootNodes, path);
        if (current && current.children === undefined) {
            const newChildren = await loadCascade(path);
            setRootNodes((prev) => updateNodeChildren(prev, path, newChildren));
        }
    }, [rootNodes, updateNodeChildren, loadCascade]);

    const handleSelectFile = useCallback(async (entry: FileEntry) => {
        setSelectedPath(entry.path);
        if (onOpenInEditor) {
            onOpenInEditor(entry.path);
            return;
        }
        if (entry.size > 100_000) {
            setError(`File too large: ${entry.name}`);
            setTimeout(() => setError(null), 3000);
            return;
        }
        try {
            const result = await getFileContent(entry.path, workspaceId);
            onInsertFile(entry.path, result.content, result.language);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to read file');
            setTimeout(() => setError(null), 3000);
        }
    }, [onInsertFile, onOpenInEditor, workspaceId]);

    const handleContextMenu = useCallback((e: React.MouseEvent, entry: FileEntry) => {
        e.preventDefault();
        setContextMenu({ x: e.clientX, y: e.clientY, entry });
    }, []);

    const handleContextAction = useCallback(async (action: string, entry: FileEntry) => {
        switch (action) {
            case 'open':
                setSelectedPath(entry.path);
                onOpenInEditor?.(entry.path);
                break;
            case 'insert': {
                if (entry.size > 100_000) { setError('File too large'); return; }
                try {
                    const result = await getFileContent(entry.path, workspaceId);
                    onInsertFile(entry.path, result.content, result.language);
                } catch (e) {
                    setError(e instanceof Error ? e.message : 'Failed to read file');
                }
                break;
            }
            case 'new-file': {
                const name = window.prompt('New file name:');
                if (!name?.trim()) return;
                const newPath = `${entry.path}/${name.trim()}`;
                try { await createFile(newPath, workspaceId); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Failed to create file'); }
                break;
            }
            case 'new-dir': {
                const name = window.prompt('New folder name:');
                if (!name?.trim()) return;
                const newPath = `${entry.path}/${name.trim()}`;
                try { await createDir(newPath, workspaceId); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Failed to create folder'); }
                break;
            }
            case 'rename': {
                const newName = window.prompt('Rename to:', entry.name);
                if (!newName?.trim() || newName.trim() === entry.name) return;
                const dir = entry.path.includes('/') ? entry.path.substring(0, entry.path.lastIndexOf('/')) : '';
                const newPath = dir ? `${dir}/${newName.trim()}` : newName.trim();
                try { await renameFile(entry.path, newPath, workspaceId); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Rename failed'); }
                break;
            }
            case 'delete': {
                if (!window.confirm(`Delete "${entry.name}"?`)) return;
                try { await deleteFile(entry.path, workspaceId); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Delete failed'); }
                break;
            }
        }
    }, [onInsertFile, onOpenInEditor, loadRoot, workspaceId]);

    return (
        <>
            {/* Toggle button — hidden in embedded mode (parent controls visibility) */}
            {!embedded && (
                <button
                    onClick={onToggle}
                    className="shrink-0 w-5 flex items-center justify-center border-r border-[var(--border)] bg-[var(--bg-secondary)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                    title={isOpen ? 'Close file tree' : 'Open file tree'}
                >
                    <ChevronRight size={12} className={`transition-transform ${isOpen ? 'rotate-180' : ''}`} />
                </button>
            )}

            {/* Panel */}
            <div
                className={
                    embedded
                        ? 'flex-1 flex flex-col min-h-0 bg-[var(--bg-secondary)] overflow-hidden'
                        : 'shrink-0 flex flex-col border-r border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden transition-all duration-150'
                }
                style={embedded ? undefined : { width: isOpen ? `${width}px` : '0px' }}
            >
                <div className="h-7 px-2 flex items-center justify-end border-b border-[var(--border)] shrink-0">
                    <button
                        onClick={loadRoot}
                        className="shrink-0 p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        title="Refresh"
                    >
                        <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>

                <div className="flex-1 overflow-y-auto py-1">
                    {loading && rootNodes.length === 0 && (
                        <div className="px-3 py-2 text-sm text-[var(--text-muted)] flex items-center gap-1.5">
                            <Loader2 size={13} className="animate-spin" /> Loading...
                        </div>
                    )}
                    {error && (
                        <div className="px-3 py-2 text-xs text-[var(--color-danger)]">{error}</div>
                    )}
                    {!loading && rootNodes.length === 0 && !error && (
                        <div className="px-3 py-2 text-sm text-[var(--text-muted)]">No files found</div>
                    )}
                    {rootNodes.map((node) => (
                        <TreeNodeItem
                            key={node.entry.path}
                            node={node}
                            depth={0}
                            onExpand={handleExpand}
                            onSelect={handleSelectFile}
                            onMentionFile={onMentionFile}
                            onContextMenu={handleContextMenu}
                            selectedPath={selectedPath}
                        />
                    ))}
                </div>
            </div>

            {contextMenu && (
                <ContextMenuPopup
                    menu={contextMenu}
                    onClose={() => setContextMenu(null)}
                    onAction={handleContextAction}
                />
            )}
        </>
    );
}
