// The documentation checks' failure paths (roadmap item 54, test-list item 2): on a tree with a
// broken relative link, a code name the tree lacks and a diagram arrow that matches no evidence,
// each check still reports the lines it reported as a .mjs script and exits 1. The expectations
// were recorded by running the pre-conversion scripts (a1cbbdfe) against this same planted tree.
//
// The checks read the tree from their working directory, so the test builds a sandbox: every entry
// of the repository root is symlinked into it (rmSync unlinks, it does not follow) and the two
// files that get broken are written fresh. The code span sits on line 1 of the architecture copy,
// so its reported line number does not drift with the document; the arrow is the diagram's only
// one into whisper-cli, and the test pins its reason, not its label.
import assert from 'node:assert/strict';
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
import { repoRoot, runCheck } from './run-check.ts';

// Never linked into the sandbox: git, this worktree's siblings and the development state.
const NOT_LINKED: Record<string, true> = {
  '.git': true,
  workspaces: true,
  '.devenv': true,
  node_modules: true,
};

// The planted breakage: a code span no rule resolves, and the box the whisper arrow points at.
const UNKNOWN_SPAN = '/api/no-such-route';
const UNKNOWN_BOX = 'Whisperr';

function plantedTree(): string {
  const root = mkdtempSync(join(tmpdir(), 'stt-docs-checks-'));
  for (const entry of readdirSync(repoRoot)) {
    if (NOT_LINKED[entry] === undefined) {
      symlinkSync(join(repoRoot, entry), join(root, entry));
    }
  }
  writeFileSync(
    join(root, 'broken.md'),
    '[a missing file](./missing.md)\n[a missing heading](./docs/architecture.md#no-such-heading)\n',
  );
  // The overview with a code name no rule resolves on line 1, and its whisper arrow pointed at a
  // box the evidence table does not know.
  const overview = readFileSync(join(repoRoot, 'docs/architecture.md'), 'utf8');
  writeFileSync(
    join(root, 'architecture.md'),
    `\`${UNKNOWN_SPAN}\`\n${overview.replace('--> Whisper\n', `--> ${UNKNOWN_BOX}\n`)}`,
  );
  return root;
}

test('the documentation checks report their failure lines and exit 1', () => {
  const root = plantedTree();
  try {
    const links = runCheck('docs/check-links.ts', ['broken.md'], root);
    assert.equal(links.status, 1, links.stderr);
    assert.equal(
      links.stderr,
      'broken.md:1: ./missing.md — no such file\n' +
        'broken.md:2: ./docs/architecture.md#no-such-heading — no heading #no-such-heading\n',
    );

    const names = runCheck('docs/check-architecture-names.ts', ['architecture.md'], root);
    assert.equal(names.status, 1, names.stderr);
    assert.equal(
      names.stderr,
      `architecture.md:1: \`${UNKNOWN_SPAN}\` — no such API prefix / data-directory path in the code\n`,
    );

    const diagram = runCheck('docs/check-architecture-diagram.ts', ['architecture.md'], root);
    assert.equal(diagram.status, 1, diagram.stderr);
    assert.match(
      diagram.stderr,
      new RegExp(
        `^arrow Engine->${UNKNOWN_BOX} \\("[^"]+"\\): no evidence of the call\n` +
          'arrow Engine->Whisper: has evidence but is not in the diagram\n$',
      ),
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
