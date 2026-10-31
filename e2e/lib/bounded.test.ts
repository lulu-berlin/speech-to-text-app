import assert from 'node:assert/strict';
import { test } from 'node:test';
import { closeWithin } from './bounded.ts';

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
