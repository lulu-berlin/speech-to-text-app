import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { darkTheme, lightTheme, themeFor } from './theme';
import { usePrefersDark } from './usePrefersDark';

describe('the theme', () => {
  it('applies the dark tokens when the OS prefers dark, and follows changes', () => {
    const listeners: (() => void)[] = [];
    const media = {
      matches: false,
      media: '(prefers-color-scheme: dark)',
      addEventListener: vi.fn((_event: string, listener: () => void) => listeners.push(listener)),
      removeEventListener: vi.fn(),
    };
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => media),
    );

    const { result } = renderHook(() => usePrefersDark());
    // The OS does not prefer dark: the light tokens apply.
    expect(themeFor(result.current)).toBe(lightTheme);
    expect(lightTheme.colors.background).not.toBe(darkTheme.colors.background);

    // The OS switches to dark: the hook follows and the dark tokens apply.
    act(() => {
      media.matches = true;
      listeners.forEach((listener) => listener());
    });
    expect(themeFor(result.current)).toBe(darkTheme);

    vi.unstubAllGlobals();
  });
});
