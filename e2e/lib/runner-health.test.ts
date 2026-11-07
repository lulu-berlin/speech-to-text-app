import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SLOW_RUNNER_FACTOR, transcriptionRatio } from './runner-health.ts';

// The probe specs with the durations CI recorded (Playwright's JSON report). `03-upload-queue`
// transcribes; the four UI-only specs in the same first five say how fast the machine is at
// everything else, so a starved runner shows up after the fifth spec instead of costing the sixth
// spec's run as well.
const healthy = [
  { file: 'tests/01-app-loads.spec.ts', ms: 2_000 },
  { file: 'tests/02-mascot-and-loading.spec.ts', ms: 4_000 },
  { file: 'tests/03-upload-queue.spec.ts', ms: 7_000 },
  { file: 'tests/04-reorder.spec.ts', ms: 1_000 },
  { file: 'tests/05-transcript.spec.ts', ms: 1_000 },
];
// #575's Windows journey: 16 s of upload-queue against 10 s of UI work.
const loaded = [
  { file: 'tests/01-app-loads.spec.ts', ms: 2_000 },
  { file: 'tests/02-mascot-and-loading.spec.ts', ms: 4_000 },
  { file: 'tests/03-upload-queue.spec.ts', ms: 16_000 },
  { file: 'tests/04-reorder.spec.ts', ms: 2_000 },
  { file: 'tests/05-transcript.spec.ts', ms: 2_000 },
];
// #544's Windows journey — loaded enough to take 64 minutes: 52 s against 11 s.
const loadedOnceMore = [
  { file: 'tests/01-app-loads.spec.ts', ms: 3_000 },
  { file: 'tests/02-mascot-and-loading.spec.ts', ms: 4_000 },
  { file: 'tests/03-upload-queue.spec.ts', ms: 52_000 },
  { file: 'tests/04-reorder.spec.ts', ms: 2_000 },
  { file: 'tests/05-transcript.spec.ts', ms: 2_000 },
];
// #605's Windows journey: 248 s of upload-queue against 10 s of UI work — the run the guard stopped.
const pathological = [
  { file: 'tests/01-app-loads.spec.ts', ms: 2_000 },
  { file: 'tests/02-mascot-and-loading.spec.ts', ms: 4_000 },
  { file: 'tests/03-upload-queue.spec.ts', ms: 248_000 },
  { file: 'tests/04-reorder.spec.ts', ms: 2_000 },
  { file: 'tests/05-transcript.spec.ts', ms: 2_000 },
];

test('healthy and merely loaded runners stay below the threshold', () => {
  const fast = transcriptionRatio(healthy);
  const slow = transcriptionRatio(loaded);
  const loadedRun = transcriptionRatio(loadedOnceMore);
  assert.ok(fast !== null, 'every probe spec ran');
  assert.ok(slow !== null);
  assert.ok(loadedRun !== null);
  assert.equal(fast.factor, 7 / 8, 'upload-queue over the UI-only specs');
  assert.equal(slow.factor, 16 / 10);
  assert.equal(loadedRun.factor, 52 / 11);
  assert.ok(fast.factor < SLOW_RUNNER_FACTOR, 'a healthy run never trips the guard');
  assert.ok(slow.factor < SLOW_RUNNER_FACTOR, 'a loaded but healthy run does not trip it');
  assert.ok(
    loadedRun.factor < SLOW_RUNNER_FACTOR,
    'even the 64-minute VM of run #544 transcribes at 4.7×, below the threshold',
  );
});

test('the pathological runner of run #605 trips the threshold after the fifth spec', () => {
  const run = transcriptionRatio(pathological);
  assert.ok(run !== null);
  assert.equal(run.transcriptionMs, 248_000);
  assert.equal(run.uiMs, 10_000);
  assert.ok(
    run.factor > SLOW_RUNNER_FACTOR,
    `the guard aborts (${run.factor}× over ${SLOW_RUNNER_FACTOR}×)`,
  );
});

test('a journey that has not run every probe spec has no verdict yet', () => {
  assert.equal(
    transcriptionRatio(healthy.slice(0, 4)),
    null,
    'the upload-queue spec alone, before the UI-only specs, is not a verdict',
  );
});

test('a run with no measured UI time has no verdict, rather than an infinite factor', () => {
  const noUi = healthy.map((spec) => (spec.file.includes('03-') ? spec : { ...spec, ms: 0 }));
  assert.equal(transcriptionRatio(noUi), null);
});
