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

  // WCAG 1.4.11 for the controls: the unchecked Switch track, the Slider rail and the
  // Slider thumb's border are `control`; the thumbs are `surfaceRaised`.
  it('the switch and slider parts meet the non-text minimum against their surroundings', () => {
    for (const surface of Object.values(surfaces)) {
      expect(contrast(colors.control, surface)).toBeGreaterThanOrEqual(AA_NON_TEXT);
    }
    // The unchecked switch thumb on its track, the slider thumb on its rail.
    expect(contrast(colors.surfaceRaised, colors.control)).toBeGreaterThanOrEqual(AA_NON_TEXT);
    // The checked switch thumb, and the slider thumb over the filled range.
    expect(contrast(colors.surfaceRaised, colors.accent)).toBeGreaterThanOrEqual(AA_NON_TEXT);
  });

  it('muted text keeps AA on the accent tint (the drop zone while dragging)', () => {
    // The same wash as the active status background; the drop zone's muted text sits on it.
    expect(contrast(colors.textMuted, colors.accentTint)).toBeGreaterThanOrEqual(AA_TEXT);
  });
});

// Item 59, finding 8: dark mode kept near-black surfaces, so cards and the dragged row
// merged into the page. A card is `surfaceRaised`; it must step clearly off both the page
// and the resting surface, and its raised shadow must be a stronger step than a card's.
describe('the dark theme elevation', () => {
  it('the raised surface steps clearly off the page and the resting surface', () => {
    expect(
      contrast(darkTheme.colors.surfaceRaised, darkTheme.colors.background),
    ).toBeGreaterThanOrEqual(1.5);
    expect(
      contrast(darkTheme.colors.surfaceRaised, darkTheme.colors.surface),
    ).toBeGreaterThanOrEqual(1.3);
  });

  it('carries a distinct raised shadow token', () => {
    const shadows = darkTheme.shadows as unknown as Record<string, string>;
    expect(shadows.raised).toBeDefined();
    expect(shadows.raised).not.toBe(shadows.card);
  });
});
