import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ConfirmBuildChip } from './ConfirmBuildChip';

describe('ConfirmBuildChip', () => {
  const mockOnConfirm = vi.fn();

  beforeEach(() => {
    mockOnConfirm.mockClear();
  });

  it('renders approve button when isVisible=true', () => {
    render(<ConfirmBuildChip sessionId="s1" isVisible={true} onConfirm={mockOnConfirm} />);
    expect(screen.getByText('Approve and Build')).toBeDefined();
  });

  it('returns null when isVisible=false', () => {
    const { container } = render(
      <ConfirmBuildChip sessionId="s1" isVisible={false} onConfirm={mockOnConfirm} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('clicking Approve calls onConfirm', () => {
    render(<ConfirmBuildChip sessionId="s1" isVisible={true} onConfirm={mockOnConfirm} />);
    fireEvent.click(screen.getByText('Approve and Build'));
    expect(mockOnConfirm).toHaveBeenCalledTimes(1);
  });
});
