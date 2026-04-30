import { useState, useCallback, useEffect } from 'react';
import {
    Folder,
    FolderOpen,
    File,
    FileCode,
    ChevronRight,
    ChevronDown,
    RefreshCw,
    Loader2,
} from 'lucide-react';
import { listFiles, getFileContent } from '@api/config';
import type { FileEntry } from '@/types/agent';

interface FileTreePanelProps {
    isOpen: boolean;
    onToggle: () => void;
    onInsertFile: (path: string, content: string, language: string) => void;
}

type TreeNode = {
    entry: FileEntry;
    children?: TreeNode[];
    loading?: boolean;
    expanded?: boolean;
};

const CODE_EXTENSIONS = new Set([
    'java', 'kt', 'kts', 'ts', 'tsx', 'js', 'jsx', 'py', 'go', 'rs',
    'rb', 'cs', 'cpp', 'cc', 'cxx', 'c', 'h', 'scala', 'groovy',
    'yaml', 'yml', 'json', 'xml', 'html', 'htm', 'css', 'scss',
    'sh', 'bash', 'sql', 'toml', 'properties', 'gradle',
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
}: {
    node: TreeNode;
    depth: number;
    onExpand: (path: string) => void;
    onSelect: (entry: FileEntry) => void;
}) {
    const { entry, children, loading, expanded } = node;
    const isDir = entry.isDir;

    return (
        <div>
            <button
                className={`w-full flex items-center gap-1.5 py-1 text-sm text-left hover:bg-[var(--bg-hover)] transition-colors truncate ${
                    !isDir ? 'text-[var(--text-secondary)]' : 'text-[var(--text-primary)]'
                }`}
                style={{ paddingLeft: `${depth * 12 + 8}px` }}
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
            {isDir && expanded && (
                <div className="transition-all" style={{ overflow: 'hidden' }}>
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

export function FileTreePanel({ isOpen, onToggle, onInsertFile }: FileTreePanelProps) {
    const [rootNodes, setRootNodes] = useState<TreeNode[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [workingDir, setWorkingDir] = useState<string>('');
    const [insertingPath, setInsertingPath] = useState<string | null>(null);

    const loadRoot = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const entries = await listFiles(undefined);
            setRootNodes(entries.map((e) => ({ entry: e })));
            if (entries.length > 0) {
                // Derive working dir from first entry's parent
                const first = entries[0];
                const parts = first.path.split('/').filter(Boolean);
                if (parts.length > 1) {
                    setWorkingDir(parts.slice(0, -1).join('/'));
                } else {
                    setWorkingDir('.');
                }
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
        if (isOpen && rootNodes.length === 0 && !loading) {
            loadRoot();
        }
    }, [isOpen]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleExpand = useCallback(async (path: string) => {
        setRootNodes((prev) => {
            // Find the node
            const findAndToggle = (nodes: TreeNode[]): TreeNode[] =>
                nodes.map((node) => {
                    if (node.entry.path === path) {
                        if (node.children !== undefined) {
                            // Already loaded, just toggle
                            return { ...node, expanded: !node.expanded };
                        }
                        // Need to load children — handled below
                        return { ...node, loading: true, expanded: true };
                    }
                    if (node.children) {
                        return { ...node, children: findAndToggle(node.children) };
                    }
                    return node;
                });
            return findAndToggle(prev);
        });

        // Check if we actually need to load
        const findNode = (nodes: TreeNode[], target: string): TreeNode | null => {
            for (const n of nodes) {
                if (n.entry.path === target) return n;
                if (n.children) {
                    const found = findNode(n.children, target);
                    if (found) return found;
                }
            }
            return null;
        };

        const current = findNode(rootNodes, path);
        if (current && current.children === undefined) {
            try {
                const entries = await listFiles(path);
                setRootNodes((prev) => {
                    const updateChildren = (nodes: TreeNode[]): TreeNode[] =>
                        nodes.map((node) => {
                            if (node.entry.path === path) {
                                return {
                                    ...node,
                                    loading: false,
                                    children: entries.map((e) => ({ entry: e })),
                                };
                            }
                            if (node.children) {
                                return { ...node, children: updateChildren(node.children) };
                            }
                            return node;
                        });
                    return updateChildren(prev);
                });
            } catch (e) {
                setRootNodes((prev) => {
                    const updateChildren = (nodes: TreeNode[]): TreeNode[] =>
                        nodes.map((node) => {
                            if (node.entry.path === path) {
                                return { ...node, loading: false, children: [] };
                            }
                            if (node.children) {
                                return { ...node, children: updateChildren(node.children) };
                            }
                            return node;
                        });
                    return updateChildren(prev);
                });
            }
        }
    }, [rootNodes]);

    const handleSelectFile = useCallback(async (entry: FileEntry) => {
        if (entry.size > 100_000) {
            // File too large — show toast-like error
            setError(`File too large: ${entry.name}`);
            setTimeout(() => setError(null), 3000);
            return;
        }
        setInsertingPath(entry.path);
        try {
            const result = await getFileContent(entry.path);
            onInsertFile(entry.path, result.content, result.language);
        } catch (e) {
            const msg = e instanceof Error ? e.message : 'Failed to read file';
            setError(msg);
            setTimeout(() => setError(null), 3000);
        } finally {
            setInsertingPath(null);
        }
    }, [onInsertFile]);

    const displayPath = workingDir.length > 30
        ? '...' + workingDir.slice(-27)
        : workingDir;

    return (
        <>
            {/* Toggle button */}
            <button
                onClick={onToggle}
                className={`shrink-0 w-5 flex items-center justify-center border-r border-[var(--border)] bg-[var(--bg-secondary)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors ${
                    isOpen ? '' : 'border-l-0'
                }`}
                title={isOpen ? 'Close file tree' : 'Open file tree'}
            >
                {isOpen
                    ? <ChevronRight size={12} />
                    : <ChevronRight size={12} />
                }
            </button>

            {/* Panel */}
            <div
                className="shrink-0 flex flex-col border-r border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden transition-all duration-150"
                style={{ width: isOpen ? '240px' : '0px' }}
            >
                {/* Header */}
                <div className="h-9 px-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
                    <span
                        className="text-xs text-[var(--text-muted)] font-mono truncate"
                        title={workingDir}
                    >
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

                {/* Tree */}
                <div className="flex-1 overflow-y-auto py-1">
                    {loading && rootNodes.length === 0 && (
                        <div className="px-3 py-2 text-sm text-[var(--text-muted)] flex items-center gap-1.5">
                            <Loader2 size={13} className="animate-spin" />
                            Loading...
                        </div>
                    )}
                    {error && !loading && rootNodes.length === 0 && (
                        <div className="px-3 py-2 text-sm text-[var(--color-danger)]">{error}</div>
                    )}
                    {!loading && rootNodes.length === 0 && !error && (
                        <div className="px-3 py-2 text-sm text-[var(--text-muted)]">
                            No files found
                        </div>
                    )}
                    {rootNodes.map((node) => (
                        <TreeNodeItem
                            key={node.entry.path}
                            node={node}
                            depth={0}
                            onExpand={handleExpand}
                            onSelect={handleSelectFile}
                        />
                    ))}
                    {insertingPath && (
                        <div className="px-3 py-1 text-xs text-[var(--color-info)] flex items-center gap-1.5">
                            <Loader2 size={12} className="animate-spin" />
                            Inserting {insertingPath}...
                        </div>
                    )}
                </div>
            </div>
        </>
    );
}
