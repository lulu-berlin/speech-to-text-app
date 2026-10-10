import { describe, expect, it } from 'vitest';
import { styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { StenographerAnimation } from './StenographerAnimation';

// The typing state is observable through the wrapper's styles: while a task runs every
// part animates; resting, the whole piece freezes (no idle motion), with the words
// visible and the sound waves hidden. styled-components keeps a rendered component's
// rules in the sheet even after unmount, so this test lives in its own file where the
// document starts fresh and the resting rules cannot leak in from earlier renders.
function restingRule(): CSSStyleRule | undefined {
  return styleRules()
    .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
    .find((rule) => rule.selectorText.endsWith(' *') && rule.cssText.includes('animation: none'));
}

describe('the stenographer typing state', () => {
  it('types while typing is set and rests otherwise: the gate freezes everything, hides the waves and keeps the words', () => {
    const typed = renderThemed(<StenographerAnimation typing />);
    // A fresh document: with typing on, no resting rule exists.
    expect(restingRule()).toBeUndefined();
    typed.unmount();

    const resting = renderThemed(<StenographerAnimation typing={false} />);
    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    // The resting gate freezes every element and comes after the animation rules, so it
    // wins the cascade.
    const rest = restingRule();
    expect(rest).toBeDefined();
    expect(rest!.cssText).toContain('animation: none');
    const flashIndex = rules.findIndex(
      (rule) =>
        rule.selectorText.includes('.keys rect') &&
        rule.style.animation !== '' &&
        rule.style.animation !== 'none',
    );
    const restingIndex = rules.indexOf(rest!);
    expect(restingIndex).toBeGreaterThan(flashIndex);
    // The words stay visible at rest; the sound waves stay hidden (no rule makes them
    // visible, and the explicit resting rule keeps them at their hidden base).
    expect(
      rules.some(
        (rule) => rule.selectorText.endsWith(' .word') && rule.cssText.includes('opacity: 1'),
      ),
    ).toBe(true);
    expect(
      rules.some(
        (rule) => rule.selectorText.endsWith(' .wave') && rule.cssText.includes('opacity: 0'),
      ),
    ).toBe(true);
    expect(
      rules.some(
        (rule) => rule.selectorText.includes('.wave') && rule.cssText.includes('opacity: 1'),
      ),
    ).toBe(false);
    resting.unmount();
  });
});
