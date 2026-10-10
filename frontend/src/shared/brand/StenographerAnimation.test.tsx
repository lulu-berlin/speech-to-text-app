import { describe, expect, it } from 'vitest';
import { required, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { StenographerAnimation } from './StenographerAnimation';

describe('the stenographer animation', () => {
  it('scopes its rules to its own class: an outside element with a class like word or head is unaffected', () => {
    const { container } = renderThemed(
      <>
        <StenographerAnimation />
        <div className="word" />
        <div className="head" />
      </>,
    );

    // The component renders an svg.
    expect(required(container.querySelector('svg'))).toBeDefined();

    // The artwork's rules are nested under the component's class: the sheet holds a
    // scoped `.genclass .word` rule, never a bare `.word` or `.head` selector.
    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    const scopedWord = rules.find((rule) => rule.selectorText.endsWith(' .word'));
    expect(scopedWord).toBeDefined();
    expect(scopedWord!.cssText).toContain('opacity: 0');
    expect(rules.some((rule) => rule.selectorText === '.word')).toBe(false);
    expect(rules.some((rule) => rule.selectorText === '.head')).toBe(false);

    // The outside elements carry no style from the component.
    const outside = [...container.querySelectorAll('div')];
    expect(outside).toHaveLength(2);
    for (const element of outside) {
      expect(getComputedStyle(element).opacity).not.toBe('0');
    }
  });
});
