import { describe, expect, it } from 'vitest';
import { styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { StenographerAnimation } from './StenographerAnimation';

// The typing state is observable through the wrapper's styles: while a task runs the
// typing parts animate; resting, they freeze and the words stay visible. styled-components
// keeps a rendered component's rules in the sheet even after unmount, so this test lives
// in its own file where the document starts fresh and the resting rules cannot leak in
// from earlier renders.
function restingWordRule(): CSSStyleRule | undefined {
  return styleRules()
    .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
    .find(
      (rule) => rule.selectorText.includes('.word') && rule.cssText.includes('animation: none'),
    );
}

describe('the stenographer typing state', () => {
  it('types while typing is set and rests otherwise: the gate freezes the typing parts and keeps the idle motion', () => {
    const typed = renderThemed(<StenographerAnimation typing />);
    // A fresh document: with typing on, no resting rule exists.
    expect(restingWordRule()).toBeUndefined();
    typed.unmount();

    const resting = renderThemed(<StenographerAnimation typing={false} />);
    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    // The resting gate freezes the typing parts and comes after the animation rules, so
    // it wins the cascade.
    const restingRule = restingWordRule();
    expect(restingRule).toBeDefined();
    expect(restingRule!.cssText).toContain('animation: none');
    const flashIndex = rules.findIndex(
      (rule) => rule.selectorText.includes('.keys rect') && rule.cssText.includes('stt-flash'),
    );
    const restingIndex = rules.indexOf(restingRule!);
    expect(restingIndex).toBeGreaterThan(flashIndex);
    // The idle parts keep their animations; the words stay visible at rest.
    expect(
      rules.some((rule) => rule.selectorText.includes('.head') && rule.cssText.includes('stt-bob')),
    ).toBe(true);
    expect(
      rules.some(
        (rule) => rule.selectorText.endsWith(' .word') && rule.cssText.includes('opacity: 1'),
      ),
    ).toBe(true);
    resting.unmount();
  });
});
