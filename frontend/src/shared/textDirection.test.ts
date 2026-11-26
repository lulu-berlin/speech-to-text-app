import { describe, expect, it } from 'vitest';
import table from '../../../docs/text-direction-cases.json';
import { letterDirection, standaloneDirection, turnDirections } from './textDirection';

// Item 76: one table of the direction rule's cases, which every implementation of the rule (the
// browser, the TUI, the window) is tested against, so they cannot drift apart. `none` is a text
// without a letter, which has no direction of its own.
describe('the shared direction cases (docs/text-direction-cases.json)', () => {
  it('holds every outcome of the rule, for single texts, turns and standalone texts', () => {
    const outcomes = new Set(['rtl', 'ltr', 'none']);
    expect(new Set(table.letters.map(({ direction }) => direction))).toEqual(outcomes);
    expect(new Set(table.turns.flatMap(({ directions }) => directions))).toEqual(outcomes);
    // A standalone text always has a direction: rtl or ltr, never none.
    expect(new Set(table.standalone.map(({ direction }) => direction))).toEqual(
      new Set(['rtl', 'ltr']),
    );
  });

  it.each(table.letters)('decides a text: $why', ({ text, direction }) => {
    expect(letterDirection(text) ?? 'none').toBe(direction);
  });

  it.each(table.turns)("decides a transcript's turns: $why", ({ texts, directions }) => {
    expect(turnDirections(texts).map((direction) => direction ?? 'none')).toEqual(directions);
  });

  it.each(table.standalone)(
    'decides a standalone name or filename: $why',
    ({ text, direction }) => {
      expect(standaloneDirection(text)).toBe(direction);
    },
  );
});
