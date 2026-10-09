import { describe, expect, it } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Skeleton } from './Skeleton';
import { Spinner } from './Spinner';

// jsdom cannot evaluate animations or media queries, so the motion is asserted on the
// emitted rules (the same approach as the button states).
function styleRules(): CSSRule[] {
  return [...document.styleSheets].flatMap((sheet) => [...sheet.cssRules]);
}

describe('the loading indicators', () => {
  it('renders a token ring and line, and stops moving under reduced motion', () => {
    const { container } = renderThemed(
      <>
        <Spinner />
        <Skeleton />
      </>,
    );

    const spinner = required(container.firstElementChild);
    const style = getComputedStyle(spinner);
    expect(style.width).toBe(px(lightTheme.iconSizes.md));
    expect(style.height).toBe(px(lightTheme.iconSizes.md));
    expect(style.borderTopWidth).toBe(px(lightTheme.borderWidths.thin));
    expect(style.borderTopStyle).toBe('solid');
    expect(style.borderTopColor).toBe(asRgb(lightTheme.colors.accent));
    expect(style.borderRadius).toBe('50%');

    const skeleton = required(spinner.nextElementSibling);
    const skeletonStyle = getComputedStyle(skeleton);
    expect(skeletonStyle.height).toBe(px(lightTheme.spacing.md));
    expect(skeletonStyle.backgroundColor).toBe(asRgb(lightTheme.colors.border));
    expect(skeletonStyle.borderRadius).toBe(lightTheme.radii.button);

    const rules = styleRules();
    // The ring spins, the line pulses — both with the token durations and easings.
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.spinDuration))).toBe(true);
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.spinEasing))).toBe(true);
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.pulseDuration))).toBe(true);
    expect(rules.some((rule) => rule.cssText.includes(lightTheme.motion.pulseEasing))).toBe(true);
    // Under reduced motion both stop: each animated rule has its own media rule that
    // turns it off (checked per component, so a missing guard is not masked by the other).
    const animatedBy = (duration: string) =>
      rules.find(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule && rule.cssText.includes(duration),
      );
    const spinnerClass = animatedBy(lightTheme.motion.spinDuration)?.selectorText;
    const skeletonClass = animatedBy(lightTheme.motion.pulseDuration)?.selectorText;
    expect(spinnerClass).toBeDefined();
    expect(skeletonClass).toBeDefined();
    const reducedMotion = rules.filter(
      (rule): rule is CSSMediaRule =>
        rule instanceof CSSMediaRule && rule.conditionText.includes('prefers-reduced-motion'),
    );
    expect(reducedMotion.length).toBeGreaterThanOrEqual(1);
    // styled-components merges identical media queries, so the two guards may share one
    // media rule; each component's own rule must still be inside it.
    const innerSelectors = reducedMotion.flatMap((media) =>
      [...media.cssRules].map((rule) => (rule as CSSStyleRule).selectorText),
    );
    expect(innerSelectors).toContain(spinnerClass!);
    expect(innerSelectors).toContain(skeletonClass!);
  });
});
