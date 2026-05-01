import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { TeamPanel } from './TeamPanel';

beforeEach(() => {
    vi.restoreAllMocks();
});

describe('TeamPanel', () => {
    it('does not render when isOpen is false', () => {
        const { container } = render(<TeamPanel isOpen={false} onClose={vi.fn()} />);
        expect(container.firstChild).toBeNull();
    });

    it('renders header when open', async () => {
        global.fetch = vi.fn().mockResolvedValue({
            json: () => Promise.resolve([]),
        });
        render(<TeamPanel isOpen={true} onClose={vi.fn()} />);
        expect(screen.getByText('Active Teams')).toBeDefined();
    });

    it('shows empty state when no teams', async () => {
        global.fetch = vi.fn().mockResolvedValue({
            json: () => Promise.resolve([]),
        });
        render(<TeamPanel isOpen={true} onClose={vi.fn()} />);
        await vi.waitFor(() => {
            expect(screen.getByText('No active teams')).toBeDefined();
        });
    });

    it('calls onClose when close button clicked', () => {
        global.fetch = vi.fn().mockResolvedValue({
            json: () => Promise.resolve([]),
        });
        const onClose = vi.fn();
        render(<TeamPanel isOpen={true} onClose={onClose} />);
        const closeBtn = screen.getByTitle('Close');
        fireEvent.click(closeBtn);
        expect(onClose).toHaveBeenCalled();
    });

    it('shows loading state initially', () => {
        global.fetch = vi.fn().mockImplementation(() => new Promise(() => {}));
        render(<TeamPanel isOpen={true} onClose={vi.fn()} />);
        expect(screen.getByText('Loading...')).toBeDefined();
    });

    it('shows team list when teams exist', async () => {
        const mockTeams = [
            {
                teamId: 'team-1',
                name: 'Alpha Team',
                goal: 'Build feature X',
                members: [],
                status: 'ACTIVE',
            },
        ];
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ json: () => Promise.resolve(mockTeams) })
            .mockResolvedValueOnce({ json: () => Promise.resolve([]) });
        render(<TeamPanel isOpen={true} onClose={vi.fn()} />);
        await vi.waitFor(() => {
            expect(screen.getByText('Alpha Team')).toBeDefined();
        });
    });

    it('shows team detail when selected', async () => {
        const mockTeams = [
            {
                teamId: 'team-1',
                name: 'Alpha Team',
                goal: 'Build feature X',
                members: [
                    { memberId: 'm1', name: 'Alice', role: 'RESEARCHER', sessionId: 's1' },
                ],
                status: 'ACTIVE',
            },
        ];
        const mockTasks = [
            { taskId: 't1', title: 'Research API', status: 'COMPLETED', ownerId: 'm1' },
            { taskId: 't2', title: 'Write tests', status: 'PENDING', ownerId: null },
        ];
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ json: () => Promise.resolve(mockTeams) })
            .mockResolvedValueOnce({ json: () => Promise.resolve(mockTasks) });
        render(<TeamPanel isOpen={true} onClose={vi.fn()} />);

        await vi.waitFor(() => {
            expect(screen.getByText('Alpha Team')).toBeDefined();
        });

        // Click the team to select it
        await act(async () => {
            fireEvent.click(screen.getByText('Alpha Team'));
        });

        await vi.waitFor(() => {
            expect(screen.getByText('Build feature X')).toBeDefined();
            expect(screen.getByText('Alice')).toBeDefined();
            expect(screen.getByText('Research API')).toBeDefined();
            expect(screen.getByText('Write tests')).toBeDefined();
        });
    });

    it('shows placeholder when no goal', async () => {
        const mockTeams = [
            {
                teamId: 'team-1',
                name: 'Empty Goal Team',
                goal: '',
                members: [],
                status: 'ACTIVE',
            },
        ];
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ json: () => Promise.resolve(mockTeams) })
            .mockResolvedValueOnce({ json: () => Promise.resolve([]) });
        render(<TeamPanel isOpen={true} onClose={vi.fn()} />);

        await vi.waitFor(() => {
            expect(screen.getByText('Empty Goal Team')).toBeDefined();
        });

        await act(async () => {
            fireEvent.click(screen.getByText('Empty Goal Team'));
        });

        await vi.waitFor(() => {
            expect(screen.getByText('\u2014')).toBeDefined();
        });
    });
});
