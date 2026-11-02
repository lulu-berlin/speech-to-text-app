import assert from 'node:assert/strict';
import { test } from 'node:test';
import { closeWithin, withinDeadline } from './bounded.ts';

// Roadmap item 84 (discovered, packaged): run #443 sat 53 minutes in the MSI spec with no trace. A
// teardown that waits on something that never answers — an app that does not close, a server
// holding a connection — must be killed at a deadline and say so, so the spec fails fast and the
// evidence steps after it still run.
test('a close that never resolves is killed at its deadline and reported', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const warn = t.mock.method(console, 'warn', () => {});
  let kills = 0;

  const outcome = closeWithin(() => new Promise<void>(() => {}), {
    deadlineMs: 60_000,
    what: 'the desktop app',
    kill: () => {
      kills += 1;
    },
  });
  t.mock.timers.tick(60_000);

  assert.equal(await outcome, 'killed');
  assert.equal(kills, 1, 'the deadline killed it once');
  assert.match(
    String(warn.mock.calls[0]?.arguments[0]),
    /the desktop app did not close within 60000 ms; killed it/,
  );
});

// Roadmap item 84 (discovered, load): a spec waited on a browser-side event with no bound at all —
// `await answered.promise` in 81-held-pause — so an event that never arrived ran the spec to the
// 20-minute test timeout, and the job with it. The wait names what it was waiting for.
test('a promise that never settles is reported naming what was awaited', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });

  const outcome = withinDeadline(new Promise<void>(() => {}), {
    what: 'the Pause request to reach the backend',
    ms: 30_000,
  });
  t.mock.timers.tick(30_000);

  await assert.rejects(
    outcome,
    /waited 30000 ms for the Pause request to reach the backend and it never came/,
  );
});

// The bound is the only change: a promise that settles in time keeps its own outcome, its value
// unwrapped and its rejection rethrown, so a spec reading the result sees what it awaited.
test('a promise that settles in time keeps its value and its rejection', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });

  const answered = Promise.withResolvers<string>();
  const value = withinDeadline(answered.promise, { what: 'the route answer', ms: 30_000 });
  answered.resolve('held response released');
  assert.equal(await value, 'held response released');

  const failed = Promise.withResolvers<void>();
  const original = new Error('the route was aborted');
  const rejection = withinDeadline(failed.promise, { what: 'the route answer', ms: 30_000 });
  failed.reject(original);
  await assert.rejects(rejection, (error) => error === original);

  // The settled waits left no deadline behind: ticking past one changes nothing.
  t.mock.timers.tick(60_000);
});
