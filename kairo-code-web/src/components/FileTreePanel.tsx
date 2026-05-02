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
    trackedFiles?: string[];
    onOpenFile?: (path: string) => void;
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
    const { entry, children, loading, expanded } = node;
    const isDir = entry.isDir;
    const isSelected = entry.path === selectedPath;

    return (
        <div>
            <div
                className={`group w-full flex items-center gap-1.5 py-1 text-sm text-left transition-colors truncate cursor-pointer ${
                    isSelected ? 'bg-[var(--color-primary)]/15' : 'hover:bg-[var(--bg-hover)]'
                }`}
                style={{ paddingLeft: `${depth * 12 + 8}px` }}
                onContextMenu={(e) => onContextMenu(e, entry)}
            >
                <button
                    className={`flex-1 flex items-center gap-1.5 min-w-0 text-left ${
                        !isDir ? 'text-[var(--text-secondary)]' : 'text-[var(--text-primary)]'
                    }`}
                    onClick={() => {
                        if (isDir) onExpand(entry.path);
                        else onSelect(entry);
                    }}
                    title={entry.path}
                >
                    {isDir && (
                        <span className="shrink-0 text-[var(--text-muted)]">
                            {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        </span>
                    )}
                    {!isDir && <span className="w-3 shrink-0" />}
                    <FileIcon name={entry.name} expanded={isDir ? expanded : undefined} />
                    <span className="truncate">{entry.name}</span>
                </button>
                {!isDir && onMentionFile && (
                    <button
                        onClick={(e) => { e.stopPropagation(); onMentionFile(entry.path); }}
                        className="shrink-0 opacity-0 group-hover:opacity-100 p-0.5 text-[var(--text-muted)] hover:text-[var(--accent)] transition-opacity"
                        title={`Mention @${entry.name}`}
                    >
                        <AtSign size={11} />
                    </button>
                )}
            </div>
            {isDir && expanded && (
                <div>
                    {loading && (
                        <div
                            className="flex items-center gap-1.5 py-1 text-xs text-[var(--text-muted)]"
                            style={{ paddingLeft: `${(depth + 1) * 12 + 8}px` }}
                        >
                            <Loader2 size={12} className="animate-spin" />
                            Loading...
                        </div>
                    )}
                    {!loading && children?.map((child) => (
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
                    {!loading && children && children.length === 0 && (
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

export function FileTreePanel({ isOpen, onToggle, onInsertFile, onMentionFile, onOpenInEditor, width = 240, trackedFiles, onOpenFile }: FileTreePanelProps) {
    const [rootNodes, setRootNodes] = useState<TreeNode[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [workingDir, setWorkingDir] = useState<string>('');
    const [selectedPath, setSelectedPath] = useState<string | undefined>(undefined);
    const [contextMenu, setContextMenu] = useState<ContextMenu | null>(null);

    const loadRoot = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const entries = await listFiles(undefined);
            setRootNodes(entries.map((e) => ({ entry: e })));
            if (entries.length > 0) {
                const first = entries[0];
                const parts = first.path.split('/').filter(Boolean);
                setWorkingDir(parts.length > 1 ? parts.slice(0, -1).join('/') : '.');
            } else {
                setWorkingDir('.');
            }
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load files');
            setRootNodes([]);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (isOpen && rootNodes.length === 0 && !loading) loadRoot();
    }, [isOpen]); // eslint-disable-line react-hooks/exhaustive-deps

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
            try {
                const entries = await listFiles(path);
                setRootNodes((prev) => updateNodeChildren(prev, path, entries.map((e) => ({ entry: e }))));
            } catch {
                setRootNodes((prev) => updateNodeChildren(prev, path, []));
            }
        }
    }, [rootNodes, updateNodeChildren]);

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
            const result = await getFileContent(entry.path);
            onInsertFile(entry.path, result.content, result.language);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to read file');
            setTimeout(() => setError(null), 3000);
        }
    }, [onInsertFile, onOpenInEditor]);

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
                    const result = await getFileContent(entry.path);
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
                try { await createFile(newPath); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Failed to create file'); }
                break;
            }
            case 'new-dir': {
                const name = window.prompt('New folder name:');
                if (!name?.trim()) return;
                const newPath = `${entry.path}/${name.trim()}`;
                try { await createDir(newPath); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Failed to create folder'); }
                break;
            }
            case 'rename': {
                const newName = window.prompt('Rename to:', entry.name);
                if (!newName?.trim() || newName.trim() === entry.name) return;
                const dir = entry.path.includes('/') ? entry.path.substring(0, entry.path.lastIndexOf('/')) : '';
                const newPath = dir ? `${dir}/${newName.trim()}` : newName.trim();
                try { await renameFile(entry.path, newPath); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Rename failed'); }
                break;
            }
            case 'delete': {
                if (!window.confirm(`Delete "${entry.name}"?`)) return;
                try { await deleteFile(entry.path); loadRoot(); } catch (e) { setError(e instanceof Error ? e.message : 'Delete failed'); }
                break;
            }
        }
    }, [onInsertFile, onOpenInEditor, loadRoot]);

    const displayPath = workingDir.length > 30 ? '...' + workingDir.slice(-27) : workingDir;

    return (
        <>
            {/* Toggle button */}
            <button
                onClick={onToggle}
                className="shrink-0 w-5 flex items-center justify-center border-r border-[var(--border)] bg-[var(--bg-secondary)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                title={isOpen ? 'Close file tree' : 'Open file tree'}
            >
                <ChevronRight size={12} className={`transition-transform ${isOpen ? 'rotate-180' : ''}`} />
            </button>

            {/* Panel */}
            <div
                className="shrink-0 flex flex-col border-r border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden transition-all duration-150"
                style={{ width: isOpen ? `${width}px` : '0px' }}
            >
                <div className="h-9 px-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
                    <span className="text-xs text-[var(--text-muted)] font-mono truncate" title={workingDir}>
                        {displayPath || '.'}
                    </span>
                    <button
                        onClick={loadRoot}
                        className="shrink-0 p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        title="Refresh"
                    >
                        <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>

                <div className="flex-1 overflow-y-auto py-1">
                    {/* Modified files summary */}
                    {trackedFiles && trackedFiles.length > 0 && onOpenFile && (
                        <div className="mb-2">
                            <div className="px-3 py-1 flex items-center gap-2">
                                <div className="flex-1 h-px bg-[var(--border)]" />
                                <span className="text-[10px] font-medium text-[var(--text-muted)] uppercase tracking-wider">Modified</span>
                                <div className="flex-1 h-px bg-[var(--border)]" />
                            </div>
                            <div className="px-3">
                                {trackedFiles.map((path) => (
                                    <button
                                        key={path}
                                        onClick={() => onOpenFile(path)}
                                        className="w-full flex items-center gap-1.5 py-0.5 text-xs text-[var(--text-secondary)]
                                            hover:text-[var(--text-primary)] transition-colors text-left truncate"
                                        title={path}
                                    >
                                        <File size={11} className="text-[var(--color-info)] shrink-0" />
                                        <span className="truncate">{path}</span>
                                    </button>
                                ))}
                            </div>
                        </div>
                    )}
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
