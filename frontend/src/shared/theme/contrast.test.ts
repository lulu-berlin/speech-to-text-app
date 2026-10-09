import { describe, expect, it } from 'vitest';
import { AA_NON_TEXT, AA_TEXT, contrast } from '../../test/wcag';
import { darkTheme, lightTheme } from './theme';
import type { StatusGroup, ThemeTokens } from './theme';

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

  it.each(['pending', 'active', 'done', 'problem', 'gone'] satisfies StatusGroup[])(
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
