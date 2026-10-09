import { screen } from '@testing-library/react';
import { Moon } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { IconButton } from './IconButton';

describe('the icon button', () => {
  it('renders a token-styled icon control with an accessible name', () => {
    renderThemed(<IconButton icon={Moon} label="Dark mode" />);
    const button = screen.getByRole('button', { name: 'Dark mode' });
    expect(getComputedStyle(button).backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(getComputedStyle(button).color).toBe(asRgb(lightTheme.colors.text));
    // The icon inside keeps its token size.
    const svg = required(button.querySelector('svg'));
    expect(getComputedStyle(svg).width).toBe(px(lightTheme.iconSizes.md));
    expect(getComputedStyle(svg).height).toBe(px(lightTheme.iconSizes.md));

    renderThemed(<IconButton icon={Moon} label="Off" disabled />);
    const off = screen.getByRole('button', { name: 'Off' });
    expect(off).toBeDisabled();
    expect(getComputedStyle(off).backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(getComputedStyle(off).color).toBe(asRgb(lightTheme.colors.textMuted));

    // The state declarations carry the secondary button tokens and the focus ring.
    const rules = [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':hover') &&
          rule.style.backgroundColor === asRgb(lightTheme.colors.surfaceRaised),
      ),
    ).toBe(true);
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':active') &&
          rule.style.backgroundColor === asRgb(lightTheme.colors.buttonSecondaryActive),
      ),
    ).toBe(true);
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':focus-visible') &&
          rule.style.boxShadow.includes(lightTheme.colors.focusRing),
      ),
    ).toBe(true);
  });
});
