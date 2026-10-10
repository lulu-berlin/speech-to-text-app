import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { ThemeProvider } from 'styled-components';
import { allStyleRules, asRgb, required, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { darkTheme, lightTheme } from '../theme/theme';
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
    // scoped `.genclass .wave` rule, never a bare `.word`, `.head` or `.wave` selector.
    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    const scopedWave = rules.find((rule) => rule.selectorText.endsWith(' .wave'));
    expect(scopedWave).toBeDefined();
    expect(scopedWave!.cssText).toContain('opacity: 0');
    for (const bare of ['.word', '.head', '.wave']) {
      expect(rules.some((rule) => rule.selectorText === bare)).toBe(false);
    }

    // The outside elements carry no style from the component.
    const outside = [...container.querySelectorAll('div')];
    expect(outside).toHaveLength(2);
    for (const element of outside) {
      expect(getComputedStyle(element).opacity).not.toBe('0');
    }
  });

  it('takes its colors from the illustration tokens: changing a token changes the rendered color', () => {
    const theme = {
      ...lightTheme,
      illustration: { ...lightTheme.illustration, accentTeal: '#123456', backdropFrom: '#abcdef' },
    };
    const { container } = render(
      <ThemeProvider theme={theme}>
        <StenographerAnimation />
      </ThemeProvider>,
    );

    // A shape that carries accentTeal (the torso) renders the changed value.
    const torso = [...container.querySelectorAll('[fill="#123456"]')];
    expect(torso.length).toBeGreaterThan(0);

    // The backdrop gradient's first stop renders the changed value.
    const backdrop = required(container.querySelector('stop'));
    expect(backdrop.getAttribute('stop-color')).toBe('#abcdef');
  });

  it('carries the artwork animations: every animated rule refers to an emitted keyframes rule, and the per-element delays are on the elements', () => {
    const { container } = renderThemed(<StenographerAnimation />);

    // jsdom cannot run animations, so the wiring is asserted: each scoped rule that
    // declares an animation references a keyframes rule the wrapper emitted. The
    // animation rules live inside the no-preference media block, hence the nested view.
    const keyframeNames = new Set(
      styleRules()
        .filter((rule): rule is CSSKeyframesRule => rule instanceof CSSKeyframesRule)
        .map((rule) => rule.name),
    );
    const animatedRules = allStyleRules()
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .filter(
        (rule) =>
          rule.style.animation !== '' &&
          // The resting and reduced-motion states freeze parts with `animation: none`;
          // those rules are not "animated parts".
          rule.style.animation !== 'none',
      );
    expect(animatedRules.length).toBeGreaterThanOrEqual(9);
    for (const rule of animatedRules) {
      const name = rule.style.animation.split(' ')[0]!;
      expect(keyframeNames.has(name)).toBe(true);
    }

    // The artwork's nth-child delays and per-class durations live on the elements now
    // (a sample: the waves, one word, the key pattern, the right arm and a finger).
    const styleOf = (selector: string) =>
      required(container.querySelector(selector)).getAttribute('style');
    expect(
      [...container.querySelectorAll('path.wave')].map((element) => element.getAttribute('style')),
    ).toEqual(['animation-delay: 0s;', 'animation-delay: -1s;', 'animation-delay: -0.5s;']);
    expect(styleOf('.word:nth-of-type(2)')).toContain('0.6s');
    expect(styleOf('.keys rect:nth-of-type(1)')).toContain('-0.55s');
    expect(styleOf('.keys rect:nth-of-type(4)')).toContain('-0.7s');
    expect(styleOf('.arm-r')).toContain('-1.1s');
    expect(styleOf('.arm-l .f1')).toContain('0.48s');
    // jsdom normalizes the trailing zero the artwork uses (-0.10s) when serializing.
    expect(styleOf('.arm-l .f1')).toContain('animation-delay: -0.1s');
  });

  it('shows the static frame under reduced motion: every animation rule sits inside a no-preference block and needs the typing class', () => {
    renderThemed(<StenographerAnimation typing />);

    // jsdom cannot evaluate media queries, so the emitted rules are asserted: the only
    // rules that declare an animation are the is-typing rules inside the
    // prefers-reduced-motion: no-preference block — under `reduce` (and at rest) no
    // rule applies, so nothing animates and no override is needed.
    const animated = allStyleRules().filter(
      (rule): rule is CSSStyleRule =>
        rule instanceof CSSStyleRule &&
        rule.style.animation !== '' &&
        rule.style.animation !== 'none',
    );
    expect(animated.length).toBeGreaterThanOrEqual(9);
    expect(
      styleRules().filter(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule &&
          rule.style.animation !== '' &&
          rule.style.animation !== 'none',
      ),
    ).toEqual([]);

    const gated = new Set(
      styleRules()
        .filter(
          (rule): rule is CSSMediaRule =>
            rule instanceof CSSMediaRule && rule.conditionText.includes('no-preference'),
        )
        .flatMap((block) =>
          [...block.cssRules].filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule),
        ),
    );
    for (const rule of animated) {
      expect(gated.has(rule)).toBe(true);
      expect(rule.selectorText).toContain('.is-typing');
    }
  });

  it('keeps the defs per instance: two components on one page have disjoint ids and self-resolving references', () => {
    const { container } = renderThemed(
      <>
        <StenographerAnimation />
        <StenographerAnimation />
      </>,
    );

    const svgs = [...container.querySelectorAll('svg')];
    expect(svgs).toHaveLength(2);
    const instances = svgs.map((svg) => {
      const ids = new Set([...svg.querySelectorAll('[id]')].map((element) => element.id));
      const references = [...svg.querySelectorAll('[fill^="url(#"], [clip-path^="url(#"]')].map(
        (element) => {
          const value = element.getAttribute('fill') ?? element.getAttribute('clip-path') ?? '';
          return value.slice(5, -1);
        },
      );
      return { ids, references };
    });

    // The two instances share no def id.
    expect(instances).toHaveLength(2);
    const first = instances[0]!;
    const second = instances[1]!;
    for (const id of first.ids) {
      expect(second.ids.has(id)).toBe(false);
    }

    // Every reference resolves within its own instance.
    for (const instance of instances) {
      expect(instance.references.length).toBeGreaterThan(0);
      for (const reference of instance.references) {
        expect(instance.ids.has(reference)).toBe(true);
      }
    }
  });

  it('is decorative everywhere: the svg carries aria-hidden and no role or label of its own', () => {
    const { container } = renderThemed(<StenographerAnimation />);
    const svg = required(container.querySelector('svg'));
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.hasAttribute('role')).toBe(false);
    expect(svg.hasAttribute('aria-label')).toBe(false);
  });

  it('resolves each theme key flash to its own theme: a light instance flashes with the light key tokens', () => {
    const renderTheme = (theme: typeof lightTheme) =>
      render(
        <ThemeProvider theme={theme}>
          <StenographerAnimation typing />
        </ThemeProvider>,
      );
    const light = renderTheme(lightTheme);
    const dark = renderTheme(darkTheme);

    // The flash rule of each instance and the keyframes name it animates.
    const flashOf = (svg: Element) => {
      const classes = svg.getAttribute('class')!.split(' ');
      const rule = classes
        .flatMap((cls) =>
          allStyleRules().filter(
            (candidate): candidate is CSSStyleRule =>
              candidate instanceof CSSStyleRule &&
              candidate.selectorText === `.${cls}.is-typing .keys rect` &&
              candidate.style.animation !== 'none',
          ),
        )
        .find(() => true);
      expect(rule).toBeDefined();
      const name = rule!.style.animation.split(' ')[0];
      // The last rule under a keyframes name wins (cascade); jsdom cannot animate.
      const frames = styleRules()
        .filter((candidate): candidate is CSSKeyframesRule => candidate instanceof CSSKeyframesRule)
        .filter((candidate) => candidate.name === name);
      expect(frames.length).toBeGreaterThan(0);
      const last = frames[frames.length - 1]!;
      const key = last.cssRules[0] as CSSKeyframeRule;
      const keyFlash = last.cssRules[1] as CSSKeyframeRule;
      return { name, key: key.style.fill, keyFlash: keyFlash.style.fill };
    };

    const lightFlash = flashOf(required(light.container.querySelector('svg')));
    const darkFlash = flashOf(required(dark.container.querySelector('svg')));

    // Each instance animates its own keyframes (the review bug: one global name made
    // the last theme's definition win for every instance).
    expect(lightFlash.name).not.toBe(darkFlash.name);
    expect(lightFlash.key).toBe(asRgb(lightTheme.illustration.key));
    expect(lightFlash.keyFlash).toBe(asRgb(lightTheme.illustration.keyFlash));
    expect(darkFlash.key).toBe(asRgb(darkTheme.illustration.key));
  });
});
