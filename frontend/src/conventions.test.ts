import { describe, expect, it } from 'vitest';

// Frontend conventions: all design values live in the tokens, so no component holds a
// literal color or size. `theme.ts` is where the values belong, so it may hold colors and
// units — but no raw `px` (user decision 2026-10-09): lengths are `rem` so they follow the
// reader's font size.
const TOKENS = '/src/shared/theme/theme.ts';

// Every source file's text, keyed by its path from the project root.
const sources = import.meta.glob<string>('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
});

// A length is a number or an interpolation followed by a unit.
const UNITS = 'px|rem|em|ex|ch|vh|vw|vmin|vmax';
const LENGTH = new RegExp(
  `(?<![A-Za-z0-9_$])(?:\\d+\\.?\\d*|\\.\\d+|\\$\\{[^{}]*\\})(?:${UNITS})\\b`,
);
const PX = new RegExp(`(?<![A-Za-z0-9_$])(?:\\d+\\.?\\d*|\\.\\d+|\\$\\{[^{}]*\\})px\\b`);
const COLOR = /#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/i;

interface Violation {
  file: string;
  line: number;
  rule: string;
  text: string;
}

function violations(): Violation[] {
  return Object.entries(sources)
    .filter(([file]) => !/\.test\.tsx?$/.test(file) && !file.startsWith('/src/test/'))
    .flatMap(([file, source]) => {
      const rules: [string, RegExp][] =
        file === TOKENS
          ? [['raw px', PX]]
          : [
              ['literal color', COLOR],
              ['literal length', LENGTH],
            ];
      return source
        .split('\n')
        .flatMap((text, index) =>
          rules
            .filter(([, pattern]) => pattern.test(text))
            .map(([rule]) => ({ file, line: index + 1, rule, text: text.trim() })),
        );
    })
    .sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line);
}

describe('the design-value convention', () => {
  it('fails on any literal color or length outside the tokens, and on raw px everywhere', () => {
    expect(violations()).toEqual([]);
  });
});
