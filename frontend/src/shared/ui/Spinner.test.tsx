import { describe, expect, it } from 'vitest';
import { asRgb, px, required, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Spinner } from './Spinner';

describe('the spinner', () => {
  it('renders a token ring and stops spinning under reduced motion', () => {
    const { container } = renderThemed(<Spinner />);

    const spinner = required(container.firstElementChild);
    const style = getComputedStyle(spinner);
    expect(style.width).toBe(px(lightTheme.iconSizes.md));
    expect(style.height).toBe(px(lightTheme.iconSizes.md));
    expect(style.borderTopWidth).toBe(px(lightTheme.borderWidths.thin));
    expect(style.borderTopStyle).toBe('solid');
    expect(style.borderTopColor).toBe(asRgb(lightTheme.colors.accent));
    expect(style.borderRadius).toBe('50%');

    const rules = styleRules();
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.spinDuration))).toBe(true);
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.spinEasing))).toBe(true);

    // Under reduced motion the ring stops (jsdom cannot evaluate media queries, so the
    // emitted rule is checked; styled-components merges identical media queries, so the
    // spinner's own class must be inside the reduced-motion block).
    const spinnerClass = rules
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .find((rule) => rule.cssText.includes(lightTheme.motion.spinDuration))?.selectorText;
    expect(spinnerClass).toBeDefined();
    const mediaSelectors = styleRules()
      .filter(
        (rule): rule is CSSMediaRule =>
          rule instanceof CSSMediaRule && rule.conditionText.includes('prefers-reduced-motion'),
      )
      .flatMap((media) => [...media.cssRules].map((rule) => (rule as CSSStyleRule).selectorText));
    expect(mediaSelectors).toContain(spinnerClass!);
  });
});
