// The review pages' staleness check (roadmap item 88, test-list line "review page"): a page whose
// translation column disagrees with its catalog is reported with its file and line, and the check
// exits 1. The check reads the tree from its working directory, so the test builds a sandbox: the
// repository's entries are symlinked in (rmSync unlinks, it does not follow), and the German page
// is written fresh with one stale row.
import assert from 'node:assert/strict';
import {
  mkdirSync,
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
import { repoRoot, runCheck } from './run-check.ts';

const NOT_LINKED: Record<string, true> = {
  '.git': true,
  workspaces: true,
  '.devenv': true,
  node_modules: true,
  docs: true,
};

const STALE = 'FALSCH';

function plantedTree(): string {
  const root = mkdtempSync(join(tmpdir(), 'stt-review-page-'));
  for (const entry of readdirSync(repoRoot)) {
    if (NOT_LINKED[entry] === undefined) {
      symlinkSync(join(repoRoot, entry), join(root, entry));
    }
  }
  const page = readFileSync(join(repoRoot, 'docs/reviews/de.md'), 'utf8');
  const stale = page.replace(
    /^\| app\.queue\s+\|[^\n]*$/m,
    `| app.queue | Queue | ${STALE} | — | — | Warteschlange |`,
  );
  assert.notEqual(stale, page, 'the German page carries an app.queue row');
  mkdirSync(join(root, 'docs/reviews'), { recursive: true });
  symlinkSync(join(repoRoot, 'docs/glossary'), join(root, 'docs/glossary'));
  for (const page of readdirSync(join(repoRoot, 'docs/reviews'))) {
    if (page !== 'de.md') {
      symlinkSync(join(repoRoot, 'docs/reviews', page), join(root, 'docs/reviews', page));
    }
  }
  writeFileSync(join(root, 'docs/reviews/de.md'), stale);
  return root;
}

test('a review page that disagrees with its catalog fails the check', () => {
  const root = plantedTree();
  try {
    const outcome = runCheck('docs/check-reviews.ts', [], root);
    assert.equal(outcome.status, 1);
    assert.match(outcome.stdout, /docs\/reviews\/de\.md:\d+: "app\.queue"/);
    assert.match(outcome.stdout, new RegExp(STALE));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a review row that names a product without its wording fails the check', () => {
  const root = plantedTree();
  try {
    const page = readFileSync(join(repoRoot, 'docs/reviews/de.md'), 'utf8');
    const bare = page.replace(
      /^\| app\.queue\s+\|[^\n]*$/m,
      '| app.queue | Queue | Warteschlange | Google 1 | — | Warteschlange |',
    );
    assert.notEqual(bare, page);
    writeFileSync(join(root, 'docs/reviews/de.md'), bare);
    const outcome = runCheck('docs/check-reviews.ts', [], root);
    assert.equal(outcome.status, 1);
    assert.match(
      outcome.stdout,
      /docs\/reviews\/de\.md:\d+: "app\.queue" names a product without its wording/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a review row whose English source cell is stale fails the check', () => {
  const root = plantedTree();
  try {
    const page = readFileSync(join(repoRoot, 'docs/reviews/de.md'), 'utf8');
    const stale = page.replace(
      /^\| app\.queue\s+\|[^\n]*$/m,
      '| app.queue | Queue (old) | Warteschlange | — | — | Warteschlange |',
    );
    assert.notEqual(stale, page);
    writeFileSync(join(root, 'docs/reviews/de.md'), stale);
    const outcome = runCheck('docs/check-reviews.ts', [], root);
    assert.equal(outcome.status, 1);
    assert.match(
      outcome.stdout,
      /docs\/reviews\/de\.md:\d+: "app\.queue" has source "Queue \(old\)", the English catalog says "Queue"/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a review row whose glossary column is wrong fails the check', () => {
  const root = plantedTree();
  try {
    const page = readFileSync(join(repoRoot, 'docs/reviews/de.md'), 'utf8');
    const stale = page.replace(
      /^\| app\.queue\s+\|[^\n]*$/m,
      '| app.queue | Queue | Warteschlange | — | — | FALSCH |',
    );
    assert.notEqual(stale, page);
    writeFileSync(join(root, 'docs/reviews/de.md'), stale);
    const outcome = runCheck('docs/check-reviews.ts', [], root);
    assert.equal(outcome.status, 1);
    assert.match(
      outcome.stdout,
      /docs\/reviews\/de\.md:\d+: "app\.queue" has glossary "FALSCH", the glossary says "Warteschlange"/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a review page that is not its generated form fails the check', () => {
  const root = plantedTree();
  try {
    // A page the derived columns cannot fault: the catalog, the glossary and every evidence cell
    // are right, only the rendered order is not the generator's. The page must still fail, so a
    // hand edit can never silently drift from `node docs/generate-reviews.ts`.
    const page = readFileSync(join(repoRoot, 'docs/reviews/de.md'), 'utf8');
    const lines = page.split('\n');
    const first = lines.findIndex((line) => /^\| error\./.test(line));
    const second = lines.findIndex((line) => /^\| app\./.test(line));
    assert.ok(first > 0 && second > first, 'the German page carries evidence rows');
    const swapped = [...lines];
    [swapped[first], swapped[second]] = [swapped[second] ?? '', swapped[first] ?? ''];
    const reordered = swapped.join('\n');
    assert.notEqual(reordered, page);
    writeFileSync(join(root, 'docs/reviews/de.md'), reordered);
    const outcome = runCheck('docs/check-reviews.ts', [], root);
    assert.equal(outcome.status, 1);
    assert.match(outcome.stdout, /docs\/reviews\/de\.md: not its generated form/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a tui row with no compared product fails the check', () => {
  const root = plantedTree();
  try {
    // Roadmap item 67, the user's translation line (2026-11-17): every `tui.*` row records a
    // compared product and its wording. A dash is no comparison, even though the generic rule
    // above accepts it for the other keys.
    const page = readFileSync(join(repoRoot, 'docs/reviews/de.md'), 'utf8');
    const bare = page.replace(
      /^\| tui\.hint\.theme\s+\|[^\n]*$/m,
      '| tui.hint.theme | [t] theme | [t] Design | — | neutral | — |',
    );
    assert.notEqual(bare, page, 'the German page carries a tui.hint.theme row');
    writeFileSync(join(root, 'docs/reviews/de.md'), bare);
    const outcome = runCheck('docs/check-reviews.ts', [], root);
    assert.equal(outcome.status, 1);
    assert.match(
      outcome.stdout,
      /docs\/reviews\/de\.md:\d+: "tui\.hint\.theme" has no compared product/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
