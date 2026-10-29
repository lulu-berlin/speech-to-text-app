// Roadmap item 75, test-list items 8 and 9 (discovered): source and test comments name the spec or
// the current behavior, and the code outside them names no oracle. A comment block that mentions
// the oracle may only be the record of a past port; any other mention is a claim about where
// behavior comes from. Comment-only lines form a block, a trailing comment stands alone.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { repoRoot } from './run-check.ts';

// The app's source and test files, backend and e2e.
const SOURCE_FILES = ['backend/src/main', 'backend/src/test', 'e2e'].flatMap((dir) =>
  readdirSync(join(repoRoot, dir), { recursive: true, encoding: 'utf8' })
    .map((file) => join(dir, file))
    .filter((file) => /\.(?:kt|ts|tsx)$/.test(file) && !file.includes('node_modules')),
);

// One line's code and comment: a `//` or `/*` outside a string literal starts the comment (a URL's
// `://` and a literal's `//` are text), string literals are blanked out of the code so their
// contents are not read as code, and `start` is 0 for a comment-only line.
function splitLine(line: string): { code: string; comment: string | null; start: number } {
  if (/^\s*(?:\/\/|\*|\/\*)/.test(line)) {
    return { code: '', comment: line, start: 0 };
  }
  let quote: string | null = null;
  let code = '';
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index]!;
    if (quote !== null) {
      code += ' ';
      if (character === '\\') {
        index += 1;
        code += ' ';
      } else if (character === quote) {
        quote = null;
      }
      continue;
    }
    if (character === '"' || character === "'" || character === '`') {
      quote = character;
      code += ' ';
      continue;
    }
    if (character === '/' && (line[index + 1] === '/' || line[index + 1] === '*')) {
      return { code, comment: line.slice(index), start: index };
    }
    code += character;
  }
  return { code, comment: null, start: -1 };
}

test('the source and test comments record only past ports', () => {
  const comments: { file: string; start: number; text: string }[] = [];
  for (const file of SOURCE_FILES) {
    let start = 0;
    let block: string[] = [];
    for (const [index, line] of readFileSync(join(repoRoot, file), 'utf8').split('\n').entries()) {
      const at = splitLine(line).start;
      if (at === 0) {
        if (block.length === 0) {
          start = index + 1;
        }
        block.push(line.trim());
      } else {
        if (block.length > 0) {
          comments.push({ file, start, text: block.join('\n') });
          block = [];
        }
        if (at > 0) {
          comments.push({ file, start: index + 1, text: line.slice(at) });
        }
      }
    }
    if (block.length > 0) {
      comments.push({ file, start, text: block.join('\n') });
    }
  }

  const violations = comments
    .filter(({ text }) => /\boracle\b/i.test(text) && !/\bport(?:ed|s|ing)?\b/i.test(text))
    .map(({ file, start, text }) => `${file}:${start}: ${text.trim()}`);
  assert.deepEqual(violations, []);
});

// Outside the comments — test descriptions, fixture names — the code names no oracle at all; a past
// port's rationale lives only in comments.
test('no source line outside a comment names the oracle', () => {
  const mentions: string[] = [];
  for (const file of SOURCE_FILES) {
    for (const [index, line] of readFileSync(join(repoRoot, file), 'utf8').split('\n').entries()) {
      const { code } = splitLine(line);
      if (/\boracle\b/i.test(code)) {
        mentions.push(`${file}:${index + 1}: ${code.trim()}`);
      }
    }
  }
  assert.deepEqual(mentions, []);
});

// The reader both tests share: a `//` inside a string literal or behind a URL's `://` is text, not
// the start of a comment, and a string's contents are not code.
test('the comment reader ignores string literals and URLs', () => {
  const withUrl = 'val url = "https://example.test" // oracle';
  assert.equal(splitLine(withUrl).start, withUrl.lastIndexOf('//'));
  assert.equal(splitLine('val s = "// not a comment"').start, -1);
  assert.equal(splitLine("val s = 'oracle'").start, -1);
  assert.equal(splitLine('val s = "oracle"').code.includes('oracle'), false);
  assert.equal(splitLine('val x = 1 // oracle').code.includes('oracle'), false);
});
