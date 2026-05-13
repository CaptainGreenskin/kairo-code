import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ConfirmBuildChip, isConfirmKeyword, isChinese } from './ConfirmBuildChip';
import { useBuildPhaseStore } from '@store/buildPhaseStore';

// Mock the buildPhaseStore
vi.mock('@store/buildPhaseStore', () => ({
  useBuildPhaseStore: vi.fn(),
}));

describe('isConfirmKeyword', () => {
  it('"go" is a confirm keyword', () => {
    expect(isConfirmKeyword('go')).toBe(true);
  });

  it('"build" is a confirm keyword', () => {
    expect(isConfirmKeyword('build')).toBe(true);
  });

  it('"开始" is a confirm keyword', () => {
    expect(isConfirmKeyword('开始')).toBe(true);
  });

  it('"approved" is a confirm keyword', () => {
    expect(isConfirmKeyword('approved')).toBe(true);
  });

  it('"ok" is a confirm keyword', () => {
    expect(isConfirmKeyword('ok')).toBe(true);
  });

  it('"start" is a confirm keyword', () => {
    expect(isConfirmKeyword('start')).toBe(true);
  });

  it('"确认" is a confirm keyword', () => {
    expect(isConfirmKeyword('确认')).toBe(true);
  });

  it('long message is not a confirm keyword', () => {
    expect(isConfirmKeyword('开始实现登录功能')).toBe(false);
  });

  it('text >= 50 chars returns false', () => {
    const long = 'a'.repeat(50);
    expect(isConfirmKeyword(long)).toBe(false);
  });

  it('non-keyword text returns false', () => {
    expect(isConfirmKeyword('let me think about this')).toBe(false);
  });

  it('random word returns false', () => {
    expect(isConfirmKeyword('hello')).toBe(false);
  });

  it('empty string returns false', () => {
    expect(isConfirmKeyword('')).toBe(false);
  });

  it('whitespace-only returns false', () => {
    expect(isConfirmKeyword('   ')).toBe(false);
  });

  it('case insensitive', () => {
    expect(isConfirmKeyword('GO')).toBe(true);
    expect(isConfirmKeyword('Build')).toBe(true);
    expect(isConfirmKeyword('APPROVED')).toBe(true);
  });

  it('trimmed whitespace matches', () => {
    expect(isConfirmKeyword('  go  ')).toBe(true);
    expect(isConfirmKeyword(' build ')).toBe(true);
  });
});

describe('isChinese', () => {
  it('returns true for Chinese keywords', () => {
    expect(isChinese('开始')).toBe(true);
    expect(isChinese('确认')).toBe(true);
    expect(isChinese('行')).toBe(true);
    expect(isChinese('做吧')).toBe(true);
  });

  it('returns false for English keywords', () => {
    expect(isChinese('go')).toBe(false);
    expect(isChinese('build')).toBe(false);
  });
});

describe('ConfirmBuildChip', () => {
  const mockOnConfirm = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    mockOnConfirm.mockClear();
    (useBuildPhaseStore as unknown as ReturnType<typeof vi.fn>).mockImplementation(
      (selector: (s: { phase: string }) => unknown) => selector({ phase: 'PLAN_PENDING' })
    );
  });

  afterEach(() => {
    vi.useRealTimers();
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

  it('5s countdown fires onConfirm', () => {
    render(<ConfirmBuildChip sessionId="s1" isVisible={true} onConfirm={mockOnConfirm} />);

    // Trigger countdown via custom event
    act(() => {
      window.dispatchEvent(new CustomEvent('kairo:startBuildCountdown', { detail: { chinese: false } }));
    });

    // Advance 5 seconds
    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(mockOnConfirm).toHaveBeenCalled();
  });

  it('cancel aborts countdown', () => {
    render(<ConfirmBuildChip sessionId="s1" isVisible={true} onConfirm={mockOnConfirm} />);

    // Start countdown
    act(() => {
      window.dispatchEvent(new CustomEvent('kairo:startBuildCountdown', { detail: { chinese: false } }));
    });

    // Cancel after 2s
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    act(() => {
      window.dispatchEvent(new Event('kairo:cancelBuildCountdown'));
    });

    // Advance remaining time — should NOT trigger
    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(mockOnConfirm).not.toHaveBeenCalled();
  });

  it('Chinese keyword shows Chinese chip text', () => {
    render(<ConfirmBuildChip sessionId="s1" isVisible={true} onConfirm={mockOnConfirm} />);

    act(() => {
      window.dispatchEvent(new CustomEvent('kairo:startBuildCountdown', { detail: { chinese: true } }));
    });

    expect(screen.getByText(/秒后自动构建/)).toBeDefined();
  });
});
