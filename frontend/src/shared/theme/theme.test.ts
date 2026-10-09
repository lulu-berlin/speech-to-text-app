import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { darkTheme, lightTheme, themeFor } from './theme';
import type { ThemeTokens } from './theme';
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

// One sRGB channel of a hex color (#rrggbb), linearized as in WCAG 2.x.
function channel(hex: string, start: number): number {
  const c = parseInt(hex.slice(start, start + 2), 16) / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

// WCAG 2.x relative luminance. Tokens are plain #rrggbb, so they can be measured.
function luminance(hex: string): number {
  expect(hex).toMatch(/^#[0-9a-f]{6}$/i);
  return 0.2126 * channel(hex, 1) + 0.7152 * channel(hex, 3) + 0.0722 * channel(hex, 5);
}

// WCAG 2.x contrast ratio, (lighter + 0.05) / (darker + 0.05).
function contrast(foreground: string, background: string): number {
  const a = luminance(foreground);
  const b = luminance(background);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

// WCAG 2.x AA: 4.5:1 for normal text (1.4.3), 3:1 for user-interface components such as
// focus indicators (1.4.11).
const AA_TEXT = 4.5;
const AA_NON_TEXT = 3;

describe.each([
  ['light', lightTheme],
  ['dark', darkTheme],
] as [string, ThemeTokens][])('the %s token set', (_name, theme) => {
  const { colors } = theme;
  const surfaces = {
    background: colors.background,
    surface: colors.surface,
    surfaceRaised: colors.surfaceRaised,
  };

  it.each(Object.entries(surfaces))('text and muted text meet AA on %s', (_surface, color) => {
    expect(contrast(colors.text, color)).toBeGreaterThanOrEqual(AA_TEXT);
    expect(contrast(colors.textMuted, color)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it('accent, accent text and danger meet AA as text', () => {
    // The accent is the link color (header navigation).
    expect(contrast(colors.accent, colors.background)).toBeGreaterThanOrEqual(AA_TEXT);
    // Text on an accent fill (primary button).
    expect(contrast(colors.accentText, colors.accent)).toBeGreaterThanOrEqual(AA_TEXT);
    expect(contrast(colors.danger, colors.background)).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it.each(['pending', 'active', 'done', 'problem', 'gone'] as const)(
    'the %s status text meets AA on its own background',
    (group) => {
      const status = colors.status[group];
      expect(contrast(status.text, status.background)).toBeGreaterThanOrEqual(AA_TEXT);
    },
  );

  it.each(Object.entries(surfaces))(
    'the focus ring meets the non-text minimum on %s',
    (_s, color) => {
      expect(contrast(colors.focusRing, color)).toBeGreaterThanOrEqual(AA_NON_TEXT);
    },
  );
});
