// The e2e durations check (roadmap item 84): where the Windows journey spends its extra time per
// spec is read from Playwright's JSON report of the Linux and the Windows journey on the same
// commit, so both jobs attach it on every run — a red or slow run is exactly the one to measure.
// Run with `node --test docs/check-e2e-durations.test.ts` from the repository root.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { assertJobOf, parseJobs, stepsOf, workflowPath } from './ci-workflow.ts';
import { repoRoot } from './run-check.ts';

test('the Linux and Windows journeys upload their per-spec durations from every run', () => {
  const config = readFileSync(join(repoRoot, 'e2e/playwright.config.ts'), 'utf8');
  assert.match(config, /\['list'\]/, 'the journey keeps its console log');
  const json = /\['json',\s*\{\s*outputFile:\s*'([^']+)'\s*\}\]/.exec(config);
  assert.ok(json, 'e2e/playwright.config.ts writes a JSON report');
  const reportPath = `e2e/${json[1] ?? ''}`;

  const jobs = parseJobs(readFileSync(workflowPath, 'utf8'));
  for (const [id, artifact] of [
    ['e2e', 'e2e-durations-linux'],
    ['windows-e2e', 'e2e-durations-windows'],
  ] as const) {
    const steps = stepsOf(assertJobOf(jobs, id));
    const journey = steps.findIndex((step) => /gradlew(\.bat)? e2e\s*$/m.test(step));
    const upload = steps.findIndex((step) => step.includes(`name: ${artifact}`));
    assert.ok(journey >= 0, `${id} runs the journey`);
    assert.ok(upload > journey, `${id} uploads ${artifact} after the journey`);
    const step = steps[upload] ?? '';
    assert.match(step, /uses: actions\/upload-artifact@v\d+/, `${id} uploads an artifact`);
    assert.match(step, /if: always\(\)/, `${id} uploads it from failed runs too`);
    assert.match(step, new RegExp(`path: ${reportPath}\\s*$`, 'm'), `${id} uploads the report`);
  }
});
