import assert from 'node:assert/strict';
import { test } from 'node:test';
import { awaitUploadOutcome } from './upload-outcome.ts';

// The clock seam the task waits use (roadmap item 84): `now` reads a fake clock and `sleep` advances
// it, so these tests take no real time.
function fakeClock(): { now(): number; sleep(ms: number): Promise<void> } {
  let clock = 0;
  return {
    now: () => clock,
    sleep: async (ms: number) => {
      clock += ms;
    },
  };
}

// Roadmap item 85 (discovered, load): CI run #555's macOS journey went red on
// 87-writes-wait-for-the-connection — the cut-off upload was polled to a fixed 120 s budget that the
// loaded runner outlasted, although the row did reach its end state moments later. The upload row's
// own text is the state: the wait must follow it, not a clock.
test('an upload row that keeps changing outlasts the old 120 s budget and lands', async () => {
  const clock = fakeClock();
  let polls = 0;
  const snapshot = async (): Promise<string | null> => {
    polls += 1;
    // A second per poll, and the resend lands only after 300 s: past the 120 s the spec used to
    // allow, but the row is visibly moving the whole time.
    return polls <= 300 ? `Uploading… ${polls}` : null;
  };

  const outcome = await awaitUploadOutcome(snapshot, {
    now: clock.now,
    sleep: clock.sleep,
  });

  assert.equal(outcome, 'landed', 'the row leaving is the resend landing');
  assert.ok(clock.now() > 120_000, 'the wait went past the 120 s budget the spec used to allow');
});

// The other end state item 87's copy names: the cut-off attempt had in fact reached the service, so
// the row carries the duplicate notice naming the task instead of disappearing.
test('a duplicate notice ends the wait as the cut-off attempt having reached the service', async () => {
  const clock = fakeClock();
  const snapshot = async (): Promise<string | null> =>
    clock.now() < 5_000 ? 'Waiting for the connection…' : 'Already uploaded — open the task';

  const outcome = await awaitUploadOutcome(snapshot, {
    now: clock.now,
    sleep: clock.sleep,
  });

  assert.equal(outcome, 'duplicate');
});

// The app's own transport message means the write did not wait: spending a budget on it would only
// delay the failure, so the wait ends on the first reading that shows it.
test('a transport error ends the wait at once instead of spending a budget', async () => {
  const clock = fakeClock();
  let polls = 0;
  const snapshot = async (): Promise<string | null> => {
    polls += 1;
    return 'jfk-upload-wait.wav Not saved — NetworkError when attempting to fetch resource.';
  };

  await assert.rejects(
    () => awaitUploadOutcome(snapshot, { now: clock.now, sleep: clock.sleep }),
    /transport error: .*Not saved/,
  );
  assert.equal(polls, 1, 'one reading is enough for a transport error');
});

// A wedged row must not hide behind the job's timeout: when its text stops changing for the stall
// window, the wait ends naming what it last said.
test('an upload row that stops changing ends the wait naming its text', async () => {
  const clock = fakeClock();
  const snapshot = async (): Promise<string | null> =>
    'jfk-upload-wait.wav Waiting for the connection…';

  await assert.rejects(
    () => awaitUploadOutcome(snapshot, { now: clock.now, sleep: clock.sleep }),
    /stopped changing for 600000 ms \(jfk-upload-wait\.wav Waiting for the connection…\)/,
  );
});
