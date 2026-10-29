// The oracle's retirement (roadmap item 75), document side: the tree holds no Python file and
// docs/oracle/ is gone (test-list item 1), the live documents name no oracle (item 2), the check
// fails a planted mention and passes the tree (item 3), ADR 0063 supersedes ADR 0002 and states
// the authority (item 4), and the backend reimplementation doc names no oracle (item 5).
// `git ls-files '*.py'` names the tracked files the repository owes nothing to; the check's own
// rule walks the tree instead of spawning git, so the same rule runs in item 3's symlinked
// sandbox. Its green line is matched on its own, so the second, live-document line does not enter
// this assertion. Item 2's rule is `liveDocumentViolations`, the one the check runs, so the test
// and the check cannot drift.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { liveDocumentViolations } from './oracle-retirement.ts';
import { repoRoot, runCheck } from './run-check.ts';

test('the tree holds no Python file and no docs/oracle', () => {
  const tracked = execFileSync('git', ['ls-files', '*.py'], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  assert.equal(tracked, '');
  assert.equal(existsSync(join(repoRoot, 'docs/oracle')), false);

  const outcome = runCheck('docs/check-oracle-retired.ts', []);
  assert.equal(outcome.status, 0, outcome.stderr);
  assert.match(outcome.stdout, /^No Python file is in the tree and docs\/oracle\/ is gone\.$/m);
});

// Roadmap item 75, test-list item 2: the documents a developer reads as instructions, an overview,
// conventions or the plan name no oracle; the roadmap may name the retirement item (`retire`), and
// the retirement record itself is the record of the removal. Historical ADRs and moved specs are
// records and are not read. The rule is the check's own, so the two cannot drift.
test('the live documents name no oracle', () => {
  assert.deepEqual(liveDocumentViolations(repoRoot), []);
});

// Roadmap item 75, test-list item 3: the check fails a live document that names the oracle, and
// passes the tree, where ADR 0002 and the moved specs still mention it. The sandbox symlinks the
// tree in (rmSync removes the link, not the file) and writes AGENTS.md afresh, so the planted line
// is real while everything else, records included, is the tree's own.
test('the check fails on a planted live-document mention and passes on the tree', async (t) => {
  const outcome = runCheck('docs/check-oracle-retired.ts', []);
  assert.equal(outcome.status, 0, outcome.stderr);
  assert.match(outcome.stdout, /^No live document names the oracle\.$/m);

  // Never linked into the sandbox: git, this worktree's siblings, the development state, and the
  // file the test replaces with a planted one.
  const notLinked: Record<string, true> = {
    '.git': true,
    workspaces: true,
    '.devenv': true,
    node_modules: true,
    'AGENTS.md': true,
  };
  const root = mkdtempSync(join(tmpdir(), 'stt-oracle-retired-'));
  try {
    try {
      for (const entry of readdirSync(repoRoot)) {
        if (notLinked[entry] === undefined) {
          symlinkSync(join(repoRoot, entry), join(root, entry));
        }
      }
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code === 'EPERM' || code === 'EACCES') {
        // Windows without Developer Mode or an elevated shell (roadmap item 79's sandbox class).
        t.skip('the sandbox needs symlinks, which this platform refuses without extra privileges');
        return;
      }
      throw error;
    }
    writeFileSync(
      join(root, 'AGENTS.md'),
      '`docs/oracle/transcribe.py` is the reference oracle.\n',
    );
    const planted = runCheck('docs/check-oracle-retired.ts', [], root);
    assert.equal(planted.status, 1, planted.stderr);
    assert.equal(
      planted.stderr,
      'The Python oracle is not retired:\n  AGENTS.md:1: names the oracle\n',
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// Roadmap item 75, test-list item 5: the document that catalogs the backend's current behavior for
// a rewrite names its authorities and its conformance rules without the oracle. It says of itself
// that superseded designs are omitted, so no oracle mention belongs anywhere in it.
test('the backend reimplementation doc names no oracle', () => {
  const doc = readFileSync(join(repoRoot, 'docs/backend-reimplementation.md'), 'utf8');
  assert.equal(/\boracle\b/i.test(doc), false);
});

// Roadmap item 75, test-list item 4: ADR 0063 supersedes ADR 0002. The index carries the
// supersession and the new row links the ADR; ADR 0002's own header carries it too, keeping its
// earlier amendment (the decisions index's convention, as 0005 and 0024 do); the ADR itself states
// the decision — the app's code, tests, specs and docs/api examples are the behavioral authority,
// the no-Python rule is unchanged, and the oracle is retired. ADR 0002 stays in place as the
// record.
test('ADR 0063 supersedes ADR 0002 and names the behavioral authority', () => {
  const index = readFileSync(join(repoRoot, 'docs/decisions/README.md'), 'utf8');
  const row = (number: string): string | undefined =>
    index.match(new RegExp(`^\\| ${number} \\|.*$`, 'm'))?.[0];

  const old = row('0002');
  assert.ok(old, 'the decisions index lists ADR 0002');
  assert.match(old, /superseded by \[0063\]\(0063-[a-z0-9-]+\.md\)/);

  const superseded = readFileSync(
    join(repoRoot, 'docs/decisions/0002-no-python-oracle-only.md'),
    'utf8',
  );
  assert.match(superseded, /^Status: superseded by \[0063\]/m);
  assert.match(superseded, /json output port dropped by \[0035\]/);

  const entry = row('0063');
  assert.ok(entry, 'the decisions index lists ADR 0063');
  const file = entry.match(/\((0063-[a-z0-9-]+\.md)\)/)?.[1];
  assert.ok(file, 'the ADR 0063 row links its file');

  const adr = readFileSync(join(repoRoot, 'docs/decisions', file), 'utf8');
  assert.match(adr, /^Status: accepted/m);
  assert.match(adr, /supersedes \[0002\]\(0002-no-python-oracle-only\.md\)/);

  // The decision itself, not the surrounding prose, names the authority and keeps the rule.
  const decision = adr.split('\n## Decision\n')[1]?.split('\n## ')[0];
  assert.ok(decision, 'ADR 0063 has a Decision section');
  assert.match(
    decision,
    /\bcode\b[^\n]*\btests\b[^\n]*\bspecs\b[^\n]*\bdocs\/api\b[^\n]*behavioral authority/i,
  );
  assert.match(decision, /\bno Python\b/i);
  assert.equal(existsSync(join(repoRoot, 'docs/decisions/0002-no-python-oracle-only.md')), true);
});
