// The action versions check (roadmap item 82): a step at actions/upload-artifact@v4 or
// actions/cache@v4 still targets Node 20, which every runner now forces onto Node 24 with a
// deprecation annotation on the run. Every workflow and composite action under `.github/` is
// scanned, not only ci.yml, because a composite action's step annotates the same run. The floors
// are the majors the repository uses now — upload-artifact v7 and cache v6 — and a lower major
// would bring the annotation back. A step's ref is read whole: a floating ref (`@main`, a branch)
// fails instead of slipping past, and a commented `uses:` line is not a step. Run with
// `node --test docs/check-action-versions.test.ts` from the repository root.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const githubDir = join(repoRoot, '.github');

// The lowest major of each action that runs on Node 24: the Node 20 majors were v4 of both (the
// deprecation annotation names them).
const NODE_24_MAJORS: Record<string, number> = {
  'actions/upload-artifact': 7,
  'actions/cache': 6,
};

// A step's action and its whole ref (`v7`, `main`, …).
const USES = /uses:\s+(actions\/upload-artifact|actions\/cache)@(\S+)/;

interface Scan {
  counts: Record<string, number>;
  problems: string[];
}

// Scans one workflow's text: every `uses:` step of the two actions — a commented line is not a
// step — whose ref must be a pinned major at or above the Node 24 floor.
export function scanActionVersions(file: string, text: string): Scan {
  const counts: Record<string, number> = {};
  const problems: string[] = [];
  for (const [index, line] of text.split('\n').entries()) {
    if (line.trimStart().startsWith('#')) continue;
    const step = USES.exec(line);
    if (step === null) continue;
    const [, action = '', ref = ''] = step;
    const floor = NODE_24_MAJORS[action] ?? 0;
    counts[action] = (counts[action] ?? 0) + 1;
    const major = /^v(\d+)$/.exec(ref);
    if (major === null) {
      problems.push(
        `${file}:${index + 1}: ${action}@${ref} is not a pinned major (@v${floor} or later)`,
      );
    } else if (Number(major[1]) < floor) {
      problems.push(
        `${file}:${index + 1}: ${action}@${ref} is older than @v${floor}, whose step carries no ` +
          'Node 20 deprecation annotation',
      );
    }
  }
  return { counts, problems };
}

// Every workflow and composite action under `.github/`, recursively.
function workflowFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return workflowFiles(path);
    return /\.ya?ml$/.test(entry.name) ? [path] : [];
  });
}

test('every upload-artifact and cache step under .github uses its Node 24 major', () => {
  const counts: Record<string, number> = {};
  const problems: string[] = [];
  for (const file of workflowFiles(githubDir)) {
    const scan = scanActionVersions(relative(repoRoot, file), readFileSync(file, 'utf8'));
    for (const [action, count] of Object.entries(scan.counts)) {
      counts[action] = (counts[action] ?? 0) + count;
    }
    problems.push(...scan.problems);
  }
  assert.deepEqual(problems, []);
  for (const [action, floor] of Object.entries(NODE_24_MAJORS)) {
    assert.ok(
      (counts[action] ?? 0) > 0,
      `a step uses ${action}@${floor} or later: the scan would otherwise pass without reading a step`,
    );
  }
});

test('the check reads the whole ref and ignores commented steps', () => {
  const scan = scanActionVersions(
    'workflow.yml',
    [
      '# uses: actions/cache@v4',
      '      - uses: actions/upload-artifact@main',
      '      - uses: actions/cache@v4',
      '      - uses: actions/upload-artifact@v7',
    ].join('\n'),
  );

  assert.deepEqual(scan.counts, { 'actions/upload-artifact': 2, 'actions/cache': 1 });
  assert.deepEqual(scan.problems, [
    'workflow.yml:2: actions/upload-artifact@main is not a pinned major (@v7 or later)',
    'workflow.yml:3: actions/cache@v4 is older than @v6, whose step carries no Node 20 ' +
      'deprecation annotation',
  ]);
});
