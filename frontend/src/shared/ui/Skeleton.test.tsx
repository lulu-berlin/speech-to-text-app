import { describe, expect, it } from 'vitest';
import { asRgb, px, required, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Skeleton } from './Skeleton';

describe('the skeleton', () => {
  it('renders a token line and stops pulsing under reduced motion', () => {
    const { container } = renderThemed(<Skeleton />);

    const skeleton = required(container.firstElementChild);
    const style = getComputedStyle(skeleton);
    expect(style.height).toBe(px(lightTheme.spacing.md));
    expect(style.backgroundColor).toBe(asRgb(lightTheme.colors.border));
    expect(style.borderRadius).toBe(lightTheme.radii.button);

    const rules = styleRules();
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.pulseDuration))).toBe(true);
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.pulseEasing))).toBe(true);

    // Under reduced motion the pulse stops (jsdom cannot evaluate media queries, so the
    // emitted rule is checked; styled-components merges identical media queries, so the
    // skeleton's own class must be inside the reduced-motion block).
    const skeletonClass = rules
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .find((rule) => rule.cssText.includes(lightTheme.motion.pulseDuration))?.selectorText;
    expect(skeletonClass).toBeDefined();
    const mediaSelectors = styleRules()
      .filter(
        (rule): rule is CSSMediaRule =>
          rule instanceof CSSMediaRule && rule.conditionText.includes('prefers-reduced-motion'),
      )
      .flatMap((media) => [...media.cssRules].map((rule) => (rule as CSSStyleRule).selectorText));
    expect(mediaSelectors).toContain(skeletonClass!);
  });
});
