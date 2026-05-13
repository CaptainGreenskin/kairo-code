import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RevertButton } from './RevertButton';

describe('RevertButton', () => {
  const mockOnRevert = vi.fn();

  beforeEach(() => {
    mockOnRevert.mockClear();
  });

  it('not rendered when phase is EXECUTING', () => {
    const { container } = render(
      <RevertButton sessionId="s1" isGit={true} phase="EXECUTING" onRevert={mockOnRevert} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('not rendered when phase is idle', () => {
    const { container } = render(
      <RevertButton sessionId="s1" isGit={true} phase="idle" onRevert={mockOnRevert} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('rendered when phase is FAILED_EXECUTION', () => {
    render(
      <RevertButton sessionId="s1" isGit={true} phase="FAILED_EXECUTION" onRevert={mockOnRevert} />
    );
    expect(screen.getByText('Revert')).toBeDefined();
  });

  it('rendered when phase is COMPLETED', () => {
    render(
      <RevertButton sessionId="s1" isGit={true} phase="COMPLETED" onRevert={mockOnRevert} />
    );
    expect(screen.getByText('Revert')).toBeDefined();
  });

  it('disabled (greyed out) when isGit=false', () => {
    render(
      <RevertButton sessionId="s1" isGit={false} phase="FAILED_EXECUTION" onRevert={mockOnRevert} />
    );
    const btn = screen.getByText('Revert').closest('button');
    expect(btn?.disabled).toBe(true);
  });

  it('shows confirmation dialog on click', () => {
    render(
      <RevertButton sessionId="s1" isGit={true} phase="FAILED_EXECUTION" onRevert={mockOnRevert} />
    );
    fireEvent.click(screen.getByText('Revert'));
    expect(screen.getByText('Revert all changes since last build?')).toBeDefined();
  });

  it('sends revert action after confirmation', () => {
    render(
      <RevertButton sessionId="s1" isGit={true} phase="FAILED_EXECUTION" onRevert={mockOnRevert} />
    );
    // Click to show confirm dialog
    fireEvent.click(screen.getByText('Revert'));
    // Click the confirm "Revert" button in the dialog
    const buttons = screen.getAllByText('Revert');
    const confirmBtn = buttons[buttons.length - 1]; // The confirm button in the dialog
    fireEvent.click(confirmBtn);
    expect(mockOnRevert).toHaveBeenCalledTimes(1);
  });

  it('cancel in confirmation dialog does not trigger revert', () => {
    render(
      <RevertButton sessionId="s1" isGit={true} phase="COMPLETED" onRevert={mockOnRevert} />
    );
    fireEvent.click(screen.getByText('Revert'));
    fireEvent.click(screen.getByText('Cancel'));
    expect(mockOnRevert).not.toHaveBeenCalled();
  });

  it('non-git workspace shows disabled tooltip', () => {
    render(
      <RevertButton sessionId="s1" isGit={false} phase="FAILED_EXECUTION" onRevert={mockOnRevert} />
    );
    const btn = screen.getByText('Revert').closest('button');
    expect(btn?.title).toBe('Revert requires a git workspace');
  });
});
