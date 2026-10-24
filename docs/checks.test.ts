// The documentation checks' green run (roadmap item 54, test-list item 1): run from the repository
// root over the tree as it stands, each check prints its one line to stdout and exits 0. The lines
// are the ones the .mjs scripts printed before the conversion (recorded on 0606ebd9), with the two
// counts — the tree's code names and diagram boxes/arrows — matched as numbers so an architecture
// change does not fail this test; the wording around them is pinned. check-exceptions is not here:
// it scans the backend, not README.md or the overview, and its own test file covers it.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runCheck } from './run-check.ts';

interface Check {
  script: string;
  args: string[];
  expected: RegExp;
}

test('the documentation checks report their green lines on the current tree', () => {
  const checks: Check[] = [
    {
      script: 'docs/check-links.ts',
      args: ['README.md', 'docs/architecture.md'],
      expected: /^All relative links resolve in README\.md, docs\/architecture\.md\.\n$/,
    },
    {
      script: 'docs/check-readme-windows.ts',
      args: [],
      expected: /^README\.md documents the Windows path\n$/,
    },
    {
      script: 'docs/check-architecture-names.ts',
      args: [],
      expected: /^All \d+ code names in docs\/architecture\.md exist in the code\.\n$/,
    },
    {
      script: 'docs/check-architecture-diagram.ts',
      args: [],
      expected:
        /^The diagram in docs\/architecture\.md matches the code: \d+ boxes, \d+ arrows\.\n$/,
    },
  ];

  for (const { script, args, expected } of checks) {
    const outcome = runCheck(script, args);
    assert.equal(outcome.status, 0, `${script} exited ${outcome.status}: ${outcome.stderr}`);
    assert.match(outcome.stdout, expected);
  }
});
