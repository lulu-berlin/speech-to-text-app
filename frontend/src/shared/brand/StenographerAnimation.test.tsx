import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { ThemeProvider } from 'styled-components';
import { asRgb, required, styleRules } from '../../test/dom';
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
    // declares an animation references a keyframes rule the wrapper emitted.
    const keyframeNames = new Set(
      styleRules()
        .filter((rule): rule is CSSKeyframesRule => rule instanceof CSSKeyframesRule)
        .map((rule) => rule.name),
    );
    const animatedRules = styleRules()
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .filter(
        (rule) =>
          rule.style.animation !== '' &&
          // The resting gate and the media fallback freeze parts with `animation: none`;
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

  it('shows the static frame under reduced motion: the fallback block is empty of animations and scoped to the component', () => {
    renderThemed(<StenographerAnimation />);

    // The component class, taken from a scoped rule the wrapper emits.
    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    const componentClass = rules
      .find((rule) => rule.selectorText.endsWith(' .word'))!
      .selectorText.split(' ')[0];

    // jsdom cannot evaluate media queries, so the emitted block is asserted.
    const media = styleRules().filter(
      (rule): rule is CSSMediaRule =>
        rule instanceof CSSMediaRule && rule.conditionText.includes('prefers-reduced-motion'),
    );
    expect(media.length).toBeGreaterThan(0);
    const mediaRules = media.flatMap((block) => [...block.cssRules]);
    const selectors = mediaRules
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .map((rule) => rule.selectorText);

    // Nothing animates, and the `*` is scoped to the component's class.
    const stop = selectors.find((selector) => selector.endsWith(' *'));
    expect(stop).toBe(`${componentClass} *`);
    expect(
      mediaRules
        .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
        .find((rule) => rule.selectorText === stop)!.cssText,
    ).toContain('animation: none !important');

    // The words are visible in the static frame; the waves are not.
    const visible = mediaRules
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .find((rule) => rule.selectorText.includes('.word'));
    expect(visible?.cssText).toContain('opacity: 1');
    // The static frame matches the resting pose: the sound waves are not made visible.
    expect(
      mediaRules
        .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
        .some((rule) => rule.selectorText.includes('.wave')),
    ).toBe(false);

    // Every rule of the block stays inside the component.
    for (const selector of selectors) {
      expect(selector.startsWith(`${componentClass} `)).toBe(true);
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
          styleRules().filter(
            (candidate): candidate is CSSStyleRule =>
              candidate instanceof CSSStyleRule &&
              candidate.selectorText === `.${cls} .keys rect` &&
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
