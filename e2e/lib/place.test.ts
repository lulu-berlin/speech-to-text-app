// Roadmap item 79, test-list item 5: the e2e package's node --test suite must pass on the Windows
// runner without symlink privileges. Windows refuses a file symlink without Developer Mode or an
// elevated shell (EPERM), so the type-check sandbox places a file it cannot link as a copy — the
// test's own sandbox is not the subject here, the placement is.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { place } from './place.ts';

test('a file the OS refuses to symlink is copied instead', () => {
  const dir = mkdtempSync(join(tmpdir(), 'stt-e2e-place-'));
  try {
    const source = join(dir, 'source.ts');
    const target = join(dir, 'target.ts');
    writeFileSync(source, 'export const value = 1;\n');
    place(source, target, {
      symlink: () => {
        throw Object.assign(new Error('EPERM: operation not permitted, symlink'), {
          code: 'EPERM',
        });
      },
    });
    assert.equal(readFileSync(target, 'utf8'), 'export const value = 1;\n');

    // A directory cannot fall back to a copy, so its refused link must surface, never pass silently.
    mkdirSync(join(dir, 'directory'));
    assert.throws(
      () =>
        place(join(dir, 'directory'), join(dir, 'directory-link'), {
          symlink: () => {
            throw Object.assign(new Error('EACCES: permission denied, symlink'), {
              code: 'EACCES',
            });
          },
        }),
      /EACCES/,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
