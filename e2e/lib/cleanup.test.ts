import assert from 'node:assert/strict';
import { test } from 'node:test';
import { removeWithRetry } from './cleanup.ts';

// A removal that throws from a script, then succeeds; records the pauses.
const scripted = (errors: (string | null)[]) => {
  const pauses: number[] = [];
  let calls = 0;
  return {
    pauses,
    get calls() {
      return calls;
    },
    remove: () => {
      const code = errors[calls] ?? null;
      calls += 1;
      if (code !== null) {
        throw Object.assign(new Error(`${code}: busy`), { code });
      }
    },
    sleep: async (ms: number) => {
      pauses.push(ms);
    },
  };
};

test('a path the OS still holds is retried until it is removed', async () => {
  const fake = scripted(['EPERM', 'EBUSY', null]);

  const removed = await removeWithRetry('C:/tmp/stt-e2e-x', {
    remove: fake.remove,
    sleep: fake.sleep,
    backoffMs: () => 250,
  });

  assert.equal(removed, true, 'the third attempt removed it');
  assert.equal(fake.calls, 3, 'two retries after the busy answers');
  assert.deepEqual(fake.pauses, [250, 250], "the pause is the caller's backoff");
});

test('a path that never goes away is reported, not thrown', async () => {
  const fake = scripted(['EPERM', 'EPERM', 'EPERM']);

  const removed = await removeWithRetry('C:/tmp/stt-e2e-x', {
    remove: fake.remove,
    sleep: fake.sleep,
    attempts: 3,
  });

  assert.equal(removed, false, 'the caller decides what a leftover means');
  assert.equal(fake.calls, 3, 'every attempt was used');
});

test('an error that is not a busy path is not retried', async () => {
  const fake = scripted(['EINVAL']);

  const removed = await removeWithRetry('C:/tmp/stt-e2e-x', {
    remove: fake.remove,
    sleep: fake.sleep,
    attempts: 5,
  });

  assert.equal(removed, false, 'the removal failed');
  assert.equal(fake.calls, 1, 'a real error is not retried');
});
