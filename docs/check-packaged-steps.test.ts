// The packaged-desktop reliability check (roadmap item 84, the Windows packaged zip hang): run #443
// sat 53 minutes in `Run the MSI installer spec` and left no trace, because a spec step had no
// timeout and the evidence upload ran only on `failure()`. Every step that runs the packaged specs
// now carries a step timeout, every packaged job's trace upload runs on any outcome, and the MSI
// call logs to a file an always-on upload keeps. Run with
// `node --test docs/check-packaged-steps.test.ts` from the repository root.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { assertJobOf, commandsOf, parseJobs, stepsOf, workflowPath } from './ci-workflow.ts';

const PACKAGED_JOBS = [
  'desktop-e2e',
  'desktop-nix-e2e',
  'desktop-bundle-e2e',
  'macos-dmg',
  'windows-desktop-zip',
] as const;

function nameOf(step: string): string {
  return /^- name: (.+)$/m.exec(step)?.[1]?.trim() ?? step.split('\n')[0] ?? '';
}

test('every packaged spec step has a timeout, and its evidence uploads on any outcome', () => {
  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  for (const id of PACKAGED_JOBS) {
    const steps = stepsOf(assertJobOf(jobs, id));
    const specSteps = steps.filter((step) =>
      /playwright\.desktop\.config\.ts|test:desktop:(nix|bundle)\b/.test(commandsOf(step)),
    );
    assert.ok(specSteps.length > 0, `${id} runs the packaged specs`);
    for (const step of specSteps) {
      assert.match(
        step,
        /^ {8}timeout-minutes: \d+$/m,
        `${id}: "${nameOf(step)}" has a step timeout`,
      );
    }
    const upload = steps.find((step) =>
      step.includes('uses: ./.github/actions/upload-test-results'),
    );
    assert.ok(upload, `${id} uploads the failed specs' traces`);
    assert.match(upload, /if: always\(\)/, `${id} uploads them on a timeout too`);
  }

  const windows = stepsOf(assertJobOf(jobs, 'windows-desktop-zip'));
  const msi = windows.find((step) => step.includes('msiexec'));
  assert.ok(msi, 'windows-desktop-zip installs the MSI');
  assert.match(msi, /\/l\*v/, 'the MSI install logs to a file');
  assert.match(msi, /msi-install\.log/, 'the log has a named path');
  const logUpload = windows.find(
    (step) => step.includes('upload-artifact') && step.includes('msi-install.log'),
  );
  assert.ok(logUpload, 'the MSI log is uploaded with its traces');
  assert.match(logUpload, /if: always\(\)/, 'the MSI log uploads on a timeout too');
});

// Roadmap item 85 (the packaged floor): `desktop-bundle-e2e` spent about five of its thirteen minutes
// building `.#desktop-bundle` (CI run #595), on every run, even when the change touched nothing the
// bundle is built from. The bundle is one self-contained file, so it is cached on its own, keyed by
// its derivation: the key changes exactly when the bundle would change, a hit skips the build, and a
// miss builds it as before. Adding it to the shared Nix store entry did not work — a cache key's
// contents cannot change, so the store entry never gained the bundle (runs #592 and #595).
test('the bundle job restores the bundle by its own derivation and builds it only on a miss', () => {
  const steps = stepsOf(
    assertJobOf(parseJobs(readFileSync(workflowPath, 'utf8')), 'desktop-bundle-e2e'),
  );
  const keyStep = steps.find((step) =>
    /nix eval --raw \.#desktop-bundle\.drvPath/.test(commandsOf(step)),
  );
  assert.ok(keyStep, 'the job evaluates the bundle derivation that keys its cache');
  const keyId = /^ {8}id: (\S+)$/m.exec(keyStep)?.[1];
  assert.ok(keyId, 'the derivation step has an id the cache key reads');

  const cacheStep = steps.find((step) => step.includes('uses: actions/cache/restore@'));
  assert.ok(cacheStep, 'the bundle is restored with actions/cache');
  assert.match(
    cacheStep,
    new RegExp(`key: stt-bundle-\\$\\{\\{ steps\\.${keyId}\\.outputs\\.key \\}\\}`),
    'the cache key is the bundle derivation',
  );
  assert.doesNotMatch(
    cacheStep,
    /restore-keys:/,
    'no prefix fallback: another derivation is another bundle, never a near miss to reuse',
  );
  const cacheId = /^ {8}id: (\S+)$/m.exec(cacheStep)?.[1];
  assert.ok(cacheId, 'the cache step has an id the build condition reads');

  const buildStep = steps.find((step) => /nix build .*\.#desktop-bundle/.test(commandsOf(step)));
  assert.ok(buildStep, 'the job still builds the bundle when it has to');
  assert.match(
    buildStep,
    new RegExp(`if: steps\\.${cacheId}\\.outputs\\.cache-hit != 'true'`),
    'it builds only when the cache had no bundle for this derivation',
  );
  assert.match(buildStep, /^ {8}timeout-minutes: \d+$/m, 'the build carries a step timeout');

  const specStep = steps.find((step) => /test:desktop:bundle\b/.test(commandsOf(step)));
  assert.ok(specStep, 'the job runs the bundle specs');
  assert.match(
    commandsOf(specStep),
    /E2E_BUNDLE_FILE=/,
    'the specs run the restored or freshly built file instead of building again',
  );
});
