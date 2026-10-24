// The e2e package's type check gates every script and spec it runs (roadmap item 54, test-list item
// 4): a type error in a converted spec fails `tsc -p tsconfig.json`, the step `pnpm --dir e2e test`
// runs before Playwright. A type check needs a tree with an error in it, so the test copies the
// package into a sandbox — every included .ts file symlinked, node_modules and tsconfig.json taken
// along, one spec copied and broken. It runs `tsc` itself: the package's test command would start
// Playwright against the real backend as well.
//
// Two errors are planted: a plain assignment mismatch, and an index read that only
// `noUncheckedIndexedAccess` rejects, so the package's strict settings are what fail it.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const packageDir = fileURLToPath(new URL('..', import.meta.url));
const tsc = join(packageDir, 'node_modules', 'typescript', 'bin', 'tsc');

// The directories the package's tsconfig includes, besides its root files.
const INCLUDED = ['lib', 'tests', 'desktop', 'desktop-tests'];

// The spec the error goes into, and the two errors: one every strict config rejects, one that only
// `noUncheckedIndexedAccess` does (`QUEUE_CLIPS` is an array, so `QUEUE_CLIPS[0]` is `… | undefined`).
const BROKEN_FILE = join('tests', '03-upload-queue.spec.ts');
const PLAIN_ERROR = "const brokenType: number = 'text';\n";
const INDEX_ERROR = 'const brokenIndex: string = QUEUE_CLIPS[0];\n';

function filesUnder(dir: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) files.push(...filesUnder(path));
    else files.push(path);
  }
  return files;
}

function sandbox(): string {
  const root = mkdtempSync(join(tmpdir(), 'stt-e2e-types-'));
  symlinkSync(join(packageDir, 'node_modules'), join(root, 'node_modules'));
  copyFileSync(join(packageDir, 'tsconfig.json'), join(root, 'tsconfig.json'));
  for (const entry of readdirSync(packageDir)) {
    if (!entry.endsWith('.ts')) continue;
    symlinkSync(join(packageDir, entry), join(root, entry));
  }
  for (const file of INCLUDED.flatMap((dir) => filesUnder(join(packageDir, dir)))) {
    const path = join(root, relative(packageDir, file));
    if (path === join(root, BROKEN_FILE)) continue;
    mkdirSync(dirname(path), { recursive: true });
    symlinkSync(file, path);
  }
  return root;
}

function breakSpec(root: string): void {
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

test('a type error in an e2e spec fails the package type check', () => {
  const root = sandbox();
  try {
    const clean = typeCheck(root);
    assert.equal(clean.status, 0, `the sandbox type-checks before the error: ${clean.output}`);

    breakSpec(root);
    const broken = typeCheck(root);
    assert.notEqual(broken.status, 0, 'the planted type errors fail the type check');
    assert.match(broken.output, /03-upload-queue\.spec\.ts\(\d+,\d+\): error TS2322/);
    assert.match(broken.output, /Type 'string' is not assignable to type 'number'/);
    assert.match(broken.output, /Type 'string \| undefined' is not assignable to type 'string'/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
