// Roadmap item 84: a missed build input would let a stale bundle pass the journey again, so the
// digest's input set is pinned here class by class — every input changes it, and nothing else does.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { ensureUiBuilt, uiDigest } from './ui-dist.ts';

// A checkout as the frontend sees it: the inputs, plus the files that must not count as ones.
function tree(): string {
  const root = mkdtempSync(join(tmpdir(), 'stt-ui-digest-'));
  const write = (path: string, text: string): void => {
    const file = join(root, path);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, text);
  };
  write('frontend/index.html', '<div id="root"></div>');
  write('frontend/vite.config.ts', 'export default {};');
  write('frontend/tsconfig.json', '{ "include": ["src"] }');
  write('frontend/package.json', '{ "name": "frontend" }');
  write('frontend/pnpm-lock.yaml', 'lockfileVersion: 9');
  write(
    'frontend/src/main.tsx',
    "import settings from '../../docs/api/settings.json';\nexport default settings;",
  );
  write('frontend/src/app.css', 'body {}');
  write('frontend/public/logo.svg', '<svg/>');
  write('docs/api/settings.json', '{ "speakersMode": "auto" }');
  write('docs/api/unused.json', '{ "unused": true }');
  write('frontend/mock/worker.js', 'self;');
  write('frontend/README.md', 'read me');
  return root;
}

test('the UI digest covers every build input class and no other file', () => {
  const cases: Array<[string, (root: string) => void, boolean]> = [
    ['a source file', (root) => edit(root, 'frontend/src/main.tsx', 'changed'), true],
    ['a new source file', (root) => edit(root, 'frontend/src/extra.ts', 'new'), true],
    [
      'a frontend test file',
      (root) => edit(root, 'frontend/src/features/App.test.tsx', 'test("x", () => {});'),
      false,
    ],
    ['a public asset', (root) => edit(root, 'frontend/public/logo.svg', '<svg id="x"/>'), true],
    ['the HTML entry', (root) => edit(root, 'frontend/index.html', '<div id="app"></div>'), true],
    ['the Vite config', (root) => edit(root, 'frontend/vite.config.ts', 'export default 1;'), true],
    ['the TypeScript config', (root) => edit(root, 'frontend/tsconfig.json', '{}'), true],
    [
      'the package manifest',
      (root) => edit(root, 'frontend/package.json', '{ "name": "x" }'),
      true,
    ],
    ['the lockfile', (root) => edit(root, 'frontend/pnpm-lock.yaml', 'lockfileVersion: 8'), true],
    [
      'a docs/api example the app imports',
      (root) => edit(root, 'docs/api/settings.json', '{ "speakersMode": "fixed" }'),
      true,
    ],
    ['the msw worker', (root) => edit(root, 'frontend/mock/worker.js', 'changed'), false],
    ['the frontend README', (root) => edit(root, 'frontend/README.md', 'changed'), false],
    [
      'a docs/api example nothing imports',
      (root) => edit(root, 'docs/api/unused.json', '{}'),
      false,
    ],
    ['the built dist', (root) => edit(root, 'frontend/dist/index.html', 'built'), false],
  ];
  for (const [label, change, changes] of cases) {
    const root = tree();
    try {
      const before = uiDigest(root);
      change(root);
      if (changes) {
        assert.notEqual(uiDigest(root), before, `${label} is a build input`);
      } else {
        assert.equal(uiDigest(root), before, `${label} is not a build input`);
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  }
});

function edit(root: string, path: string, text: string): void {
  const file = join(root, path);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, text);
}

test('the journey rebuilds the UI when an input changed and reuses it when nothing did', async () => {
  const root = tree();
  try {
    let builds = 0;
    const build = async (): Promise<void> => {
      builds += 1;
      edit(root, 'frontend/dist/index.html', '<built>');
      edit(root, 'frontend/dist/assets/app.js', 'app();');
    };

    assert.equal(await ensureUiBuilt({ root, build }), 'rebuilt', 'no dist before the first run');
    assert.equal(builds, 1);

    assert.equal(await ensureUiBuilt({ root, build }), 'reused', 'the sources still match dist');
    assert.equal(builds, 1, 'a current dist is not rebuilt');

    edit(root, 'frontend/src/main.tsx', 'changed');
    assert.equal(await ensureUiBuilt({ root, build }), 'rebuilt', 'a changed source rebuilds');
    assert.equal(builds, 2);

    edit(root, 'frontend/pnpm-lock.yaml', 'lockfileVersion: 8');
    assert.equal(await ensureUiBuilt({ root, build }), 'rebuilt', 'a changed lockfile rebuilds');
    assert.equal(builds, 3);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
