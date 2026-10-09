import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { asRgb } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { AA_TEXT, contrast } from '../../test/wcag';
import { darkTheme, lightTheme } from '../theme/theme';
import { Button } from './Button';

// The string-valued colour roles the button uses (speakerTints and status are excluded).
type ColorKey =
  | 'accent'
  | 'surface'
  | 'text'
  | 'danger'
  | 'accentText'
  | 'surfaceRaised'
  | 'buttonPrimaryHover'
  | 'buttonPrimaryActive'
  | 'buttonSecondaryActive'
  | 'buttonDangerHover'
  | 'buttonDangerActive';

// Every variant's colour pair must keep AA through hover and active in both themes.
const STATES: [ColorKey, ColorKey[], ColorKey][] = [
  ['accent', ['buttonPrimaryHover', 'buttonPrimaryActive'], 'accentText'],
  ['surface', ['surfaceRaised', 'buttonSecondaryActive'], 'text'],
  ['danger', ['buttonDangerHover', 'buttonDangerActive'], 'accentText'],
];

describe('the button', () => {
  it('renders its variants and states with token colours', () => {
    // Every variant's text keeps AA on its own background, hover and active.
    for (const theme of [lightTheme, darkTheme]) {
      for (const [background, hoverActive, text] of STATES) {
        for (const state of [
          theme.colors[background],
          ...hoverActive.map((key) => theme.colors[key]),
        ]) {
          expect(contrast(theme.colors[text], state)).toBeGreaterThanOrEqual(AA_TEXT);
        }
      }
    }

    renderThemed(
      <>
        <Button>Save</Button>
        <Button variant="secondary">Cancel</Button>
        <Button variant="danger">Delete</Button>
        <Button disabled>Off</Button>
        <Button loading>Saving</Button>
      </>,
    );

    const primary = screen.getByRole('button', { name: 'Save' });
    expect(getComputedStyle(primary).backgroundColor).toBe(asRgb(lightTheme.colors.accent));
    expect(getComputedStyle(primary).color).toBe(asRgb(lightTheme.colors.accentText));

    const secondary = screen.getByRole('button', { name: 'Cancel' });
    expect(getComputedStyle(secondary).backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(getComputedStyle(secondary).color).toBe(asRgb(lightTheme.colors.text));

    const danger = screen.getByRole('button', { name: 'Delete' });
    expect(getComputedStyle(danger).backgroundColor).toBe(asRgb(lightTheme.colors.danger));
    expect(getComputedStyle(danger).color).toBe(asRgb(lightTheme.colors.accentText));

    const off = screen.getByRole('button', { name: 'Off' });
    expect(off).toBeDisabled();
    expect(getComputedStyle(off).backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(getComputedStyle(off).color).toBe(asRgb(lightTheme.colors.textMuted));

    const saving = screen.getByRole('button', { name: 'Saving' });
    expect(saving).toBeDisabled();
    expect(saving).toHaveAttribute('aria-busy', 'true');
    expect(getComputedStyle(saving).backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(getComputedStyle(saving).color).toBe(asRgb(lightTheme.colors.textMuted));

    // The hover, active and focus-visible declarations carry the state tokens: jsdom cannot
    // apply pseudo-classes, so the emitted rules are read instead.
    const rules = [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    const hasHover = (color: string) =>
      rules.some(
        (rule) => rule.selectorText.includes(':hover') && rule.style.backgroundColor === color,
      );
    const hasActive = (color: string) =>
      rules.some(
        (rule) => rule.selectorText.includes(':active') && rule.style.backgroundColor === color,
      );
    expect(hasHover(asRgb(lightTheme.colors.buttonPrimaryHover))).toBe(true);
    expect(hasActive(asRgb(lightTheme.colors.buttonPrimaryActive))).toBe(true);
    expect(hasHover(asRgb(lightTheme.colors.surfaceRaised))).toBe(true);
    expect(hasActive(asRgb(lightTheme.colors.buttonSecondaryActive))).toBe(true);
    expect(hasHover(asRgb(lightTheme.colors.buttonDangerHover))).toBe(true);
    expect(hasActive(asRgb(lightTheme.colors.buttonDangerActive))).toBe(true);
    // The ring itself is the focusRing token, so the keyboard focus stays visible.
    // jsdom keeps the rule's box-shadow verbatim, hex included.
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':focus-visible') &&
          rule.style.boxShadow.includes(lightTheme.colors.focusRing),
      ),
    ).toBe(true);
  });
});
