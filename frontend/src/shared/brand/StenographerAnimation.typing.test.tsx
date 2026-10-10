import { describe, expect, it } from 'vitest';
import { allStyleRules, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { StenographerAnimation } from './StenographerAnimation';

// The typing state is a class on the svg: while a task runs the wrapper's animation
// rules match (`is-typing`); resting, no rule matches, so the piece is static by
// construction — no overrides to fight the cascade. The animation rules also sit inside
// a prefers-reduced-motion: no-preference block, so a reduced-motion preference
// disables them the same way. styled-components keeps a rendered component's rules in
// the sheet even after unmount, so this test lives in its own file where the document
// starts fresh.
function animatedRules(): CSSStyleRule[] {
  return allStyleRules().filter(
    (rule): rule is CSSStyleRule =>
      rule instanceof CSSStyleRule &&
      rule.style.animation !== '' &&
      rule.style.animation !== 'none',
  );
}

describe('the stenographer typing state', () => {
  it('types while typing is set and rests otherwise: the is-typing class gates every animation rule', () => {
    const typed = renderThemed(<StenographerAnimation typing />);
    expect(typed.container.querySelector('svg')!.getAttribute('class')).toContain('is-typing');
    const rules = animatedRules();
    expect(rules.length).toBeGreaterThanOrEqual(9);
    // Every animation requires the typing class and the no-preference media block, so
    // resting and reduced motion have no matching animation at all.
    for (const rule of rules) {
      expect(rule.selectorText).toContain('.is-typing');
    }
    expect(
      styleRules().some(
        (rule): rule is CSSMediaRule =>
          rule instanceof CSSMediaRule && rule.conditionText.includes('no-preference'),
      ),
    ).toBe(true);
    typed.unmount();

    const resting = renderThemed(<StenographerAnimation typing={false} />);
    const svg = resting.container.querySelector('svg')!;
    expect(svg.getAttribute('class')).not.toContain('is-typing');
    // The static frame: any rule that hides the words is gated behind is-typing (so the
    // words are visible at rest), and the waves stay at their hidden base.
    const cssRules = allStyleRules().filter(
      (rule): rule is CSSStyleRule => rule instanceof CSSStyleRule,
    );
    for (const rule of cssRules) {
      if (rule.selectorText.includes('.word') && rule.cssText.includes('opacity: 0')) {
        expect(rule.selectorText).toContain('.is-typing');
      }
    }
    expect(
      cssRules.some(
        (rule) => rule.selectorText.endsWith(' .wave') && rule.cssText.includes('opacity: 0'),
      ),
    ).toBe(true);
    resting.unmount();
  });
});
