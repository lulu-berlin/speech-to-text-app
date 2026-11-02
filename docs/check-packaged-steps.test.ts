// The packaged-desktop reliability check (roadmap item 84, the Windows packaged zip hang): run #443
// sat 53 minutes in `Run the MSI installer spec` and left no trace, because a spec step had no
// timeout and the evidence upload ran only on `failure()`. Every step that runs the packaged specs
// now carries a step timeout, every packaged job's trace upload runs on any outcome, and the MSI
// call logs to a file an always-on upload keeps. Run with
// `node --test docs/check-packaged-steps.test.ts` from the repository root.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { assertJobOf, parseJobs, stepsOf, workflowPath } from './ci-workflow.ts';

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

// The step's own lines without comments: stepsOf folds the next block's comments into the last
// step, and a comment mentioning the desktop config is not a spec run.
function commandsOf(step: string): string {
  return step
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
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
