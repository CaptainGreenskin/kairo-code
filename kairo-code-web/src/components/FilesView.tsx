import { FileTreePanel } from './FileTreePanel';
import { useOpenFilesStore } from '@store/openFilesStore';

interface FilesViewProps {
    workspaceId?: string;
    rootKey?: string;
    onInsertFile: (path: string, content: string, language: string) => void;
    onMentionFile?: (path: string) => void;
}

/**
 * Explorer view embedded in PrimarySidebar — wraps FileTreePanel and routes
 * file open clicks through openFilesStore so they land as tabs in the central
 * editor area.
 */
export function FilesView({ workspaceId, rootKey, onInsertFile, onMentionFile }: FilesViewProps) {
    const openFile = useOpenFilesStore((s) => s.openFile);

    return (
        <FileTreePanel
            embedded
            isOpen
            onToggle={() => {}}
            onInsertFile={onInsertFile}
            onMentionFile={onMentionFile}
            onOpenInEditor={(path) => openFile(path)}
            rootKey={rootKey}
            workspaceId={workspaceId}
        />
    );
}
