// The docs package's type check gates the checks it runs (roadmap item 54, test-list item 4): a type
// error in a converted check fails `tsc --noEmit`, the step `pnpm --dir docs test` starts with.
// A type check needs a tree with an error in it, so the test copies the package into a sandbox —
// every included .ts file symlinked, node_modules and tsconfig.json taken along, one check copied
// and broken. It runs `tsc` itself, never `pnpm --dir docs test`: that command ends in this file.
//
// Two errors are planted: a plain assignment mismatch, and an index read that only
// `noUncheckedIndexedAccess` rejects, so the package's strict settings are what fail it.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const packageDir = fileURLToPath(new URL('.', import.meta.url));
const tsc = join(packageDir, 'node_modules', 'typescript', 'bin', 'tsc');

// The check the error goes into, and the two errors: one every strict config rejects, one that only
// `noUncheckedIndexedAccess` does (`files` is a `string[]`, so `files[0]` is `string | undefined`).
const BROKEN_FILE = 'check-links.ts';
const PLAIN_ERROR = "const brokenType: number = 'text';\n";
const INDEX_ERROR = 'const brokenIndex: string = files[0];\n';

function sandbox(): string {
  const root = mkdtempSync(join(tmpdir(), 'stt-docs-types-'));
  symlinkSync(join(packageDir, 'node_modules'), join(root, 'node_modules'));
  writeFileSync(join(root, 'tsconfig.json'), readFileSync(join(packageDir, 'tsconfig.json')));
  for (const entry of readdirSync(packageDir)) {
    if (!entry.endsWith('.ts') || entry === BROKEN_FILE) continue;
    symlinkSync(join(packageDir, entry), join(root, entry));
  }
  return root;
}

function breakCheck(root: string): void {
  const source = readFileSync(join(packageDir, BROKEN_FILE), 'utf8');
  writeFileSync(join(root, BROKEN_FILE), `${source}\n${PLAIN_ERROR}${INDEX_ERROR}`);
}

function typeCheck(root: string): { status: number; output: string } {
  const result = spawnSync(process.execPath, [tsc, '-p', join(root, 'tsconfig.json')], {
    cwd: root,
    encoding: 'utf8',
  });
  return { status: result.status ?? -1, output: `${result.stdout}${result.stderr}` };
}

test('a type error in a documentation check fails the package type check', () => {
  const root = sandbox();
  try {
    const clean = typeCheck(root);
    assert.equal(clean.status, 0, `the sandbox type-checks before the error: ${clean.output}`);

    breakCheck(root);
    const broken = typeCheck(root);
    assert.notEqual(broken.status, 0, 'the planted type errors fail the type check');
    assert.match(broken.output, /check-links\.ts\(\d+,\d+\): error TS2322/);
    assert.match(broken.output, /Type 'string' is not assignable to type 'number'/);
    assert.match(broken.output, /Type 'string \| undefined' is not assignable to type 'string'/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
