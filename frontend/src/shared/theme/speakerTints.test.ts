import { describe, expect, it } from 'vitest';
import { contrast } from '../../test/wcag';
import { darkTheme, lightTheme, speakerTint } from './theme';
import type { ThemeTokens } from './theme';

// Item 12 allows up to 20 speakers, so the palette carries one tint per speaker index.
const SPEAKERS = 20;
// WCAG 2.x AA for normal text (1.4.3).
const AA_TEXT = 4.5;

// A speaker's name is shown in its tint (item 15), so every tint must be readable on every
// surface it can sit on, in both themes.
describe('the speaker palette', () => {
  it('provides one tint per speaker index, wraps around, and meets AA in both themes', () => {
    for (const [name, theme] of [
      ['light', lightTheme],
      ['dark', darkTheme],
    ] as [string, ThemeTokens][]) {
      const tints = theme.colors.speakerTints;
      expect(tints, `${name} palette size`).toHaveLength(SPEAKERS);
      // Two speakers sharing a tint would defeat the point of the palette.
      expect(new Set(tints).size, `${name} distinct tints`).toBe(SPEAKERS);

      const surfaces = [theme.colors.background, theme.colors.surface, theme.colors.surfaceRaised];
      for (const tint of tints) {
        for (const surface of surfaces) {
          expect(contrast(tint, surface), `${name} tint ${tint}`).toBeGreaterThanOrEqual(AA_TEXT);
        }
      }

      // The speaker index is item 12's 0-based speakerId, taken modulo the palette size.
      expect(speakerTint(tints, 0), `${name} first speaker`).toBe(tints[0]);
      expect(speakerTint(tints, SPEAKERS - 1), `${name} last speaker`).toBe(tints[SPEAKERS - 1]);
      expect(speakerTint(tints, SPEAKERS), `${name} wraps`).toBe(tints[0]);
      expect(speakerTint(tints, SPEAKERS + 1), `${name} wraps again`).toBe(tints[1]);
    }
  });
});
