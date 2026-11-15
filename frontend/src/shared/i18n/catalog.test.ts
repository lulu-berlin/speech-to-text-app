import { describe, expect, it } from 'vitest';
import { findUserVisibleLiterals } from './catalogCheck';

// Every source file's text, keyed by its path from the project root (the conventions
// check's pattern).
const sources = import.meta.glob<string>('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
});

// The check covers the whole frontend: every source file except the tests and their
// helpers (the conventions check's filter). A new screen cannot add a literal silently.
const SCOPE = Object.keys(sources).filter(
  (file) => !/\.test\.tsx?$/.test(file) && !file.startsWith('/src/test/'),
);

describe('the message catalog check', () => {
  it('reports user-visible literals outside the catalog, ignores never-translated ones, and finds the migrated screen clean', () => {
    // A planted screen: an aria-label, a title, JSX text, and two never-translated strings
    // (the product name and a file extension) that must not be reported.
    const planted = [
      'export function Example() {',
      '  return (',
      '    <div aria-label="Close dialog" title="Tooltip">',
      '      Save changes',
      '      <span>Speech-to-Text</span>',
      '      <span>.wav</span>',
      '      <span>de</span>',
      '      <span>Deutsch</span>',
      '    </div>',
      '  );',
      '}',
    ].join('\n');
    expect(findUserVisibleLiterals(planted, 'planted.tsx')).toEqual([
      { file: 'planted.tsx', line: 3, text: 'Close dialog' },
      { file: 'planted.tsx', line: 3, text: 'Tooltip' },
      { file: 'planted.tsx', line: 4, text: 'Save changes' },
    ]);

    const violations = SCOPE.flatMap((file) => {
      const source = sources[file];
      if (source === undefined) {
        throw new Error(`missing source ${file}`);
      }
      return findUserVisibleLiterals(source, file);
    });
    expect(violations).toEqual([]);
  });
});
