import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { TaskSnapshot } from './await-task.ts';
import { BackendUnanswered, resetQueue } from './reset-queue.ts';

function task(id: number, status: string): TaskSnapshot {
  return {
    id,
    filename: `clip-${id}.wav`,
    status,
    phase: status === 'RUNNING' ? 'TRANSCRIBING' : null,
    transcriptionProgress: 0,
    diarizationStage: null,
    diarizationProgress: 0,
    error: null,
    finishedAt: null,
  };
}

// Roadmap item 84 (discovered, load): CI run #452 failed two Windows specs because earlier specs left
// clips queued and running. Before each spec the journey cancels every pending task and resumes a
// paused queue, through the API, and starts only once nothing is pending — ending on state, not on a
// clock.
test('the reset cancels every pending task once, resumes a paused queue, and ends when nothing is pending', async () => {
  let clock = 0;
  const tasks = new Map<number, TaskSnapshot>([
    [1, task(1, 'QUEUED')],
    [2, task(2, 'RUNNING')],
    [3, task(3, 'PAUSED')],
    [4, task(4, 'COMPLETED')],
  ]);
  let paused = true;
  const canceled: number[] = [];
  let resumes = 0;
  // A running task takes a few polls to die (the backend awaits the process); the others end at once.
  const dying = new Map<number, number>();

  const done = resetQueue(
    {
      snapshot: async () => {
        for (const [id, at] of dying) {
          if (clock >= at) {
            tasks.set(id, task(id, 'CANCELED'));
            dying.delete(id);
          }
        }
        return { tasks: [...tasks.values()], paused };
      },
      cancel: async (id) => {
        canceled.push(id);
        if (tasks.get(id)?.status === 'RUNNING') dying.set(id, clock + 3_000);
        else tasks.set(id, task(id, 'CANCELED'));
      },
      resume: async () => {
        resumes += 1;
        paused = false;
      },
    },
    {
      stallMs: 600_000,
      now: () => clock,
      sleep: async (ms) => {
        clock += ms;
        if (clock > 60_000) throw new Error('the reset never ended');
      },
    },
  );
  await done;

  assert.deepEqual(canceled.toSorted(), [1, 2, 3], 'each pending task is canceled exactly once');
  assert.equal(resumes, 1, 'the paused queue is resumed once');
  assert.equal(tasks.get(4)?.status, 'COMPLETED', 'a finished task is left alone');
  assert.equal(tasks.get(2)?.status, 'CANCELED', 'the reset waited for the running task to end');
});

// Roadmap item 84 (discovered, load): a pending task that never ends — a cancel the backend cannot
// complete — must not hold the journey until the job's timeout. When nothing in the task list or the
// queue state changes for the stall window, the reset fails naming the tasks still pending.
test('a queue that stops changing ends the reset naming the stuck tasks', async () => {
  let clock = 0;
  const stuck = task(7, 'RUNNING');

  const reset = resetQueue(
    {
      snapshot: async () => ({ tasks: [stuck, task(8, 'COMPLETED')], paused: false }),
      cancel: async () => {},
      resume: async () => {},
    },
    {
      stallMs: 600_000,
      now: () => clock,
      sleep: async (ms) => {
        clock += ms;
        if (clock > 1_200_000) throw new Error('the reset did not stop on a stalled queue');
      },
    },
  );

  await assert.rejects(reset, /no progress for 600000 ms .*task 7 clip-7\.wav RUNNING/);
});

// Roadmap item 84 (discovered, load): a restart spec can leave the backend down. The reset is not the
// spec's assertion, so it steps aside with a warning and the spec then fails on its own wait, under
// its own name, instead of the fixture's.
test('a backend that does not answer makes the reset warn and step aside', async (t) => {
  const warn = t.mock.method(console, 'warn', () => {});
  let cancels = 0;

  await resetQueue(
    {
      snapshot: async () => {
        throw new BackendUnanswered('connect ECONNREFUSED 127.0.0.1:41234');
      },
      cancel: async () => {
        cancels += 1;
      },
      resume: async () => {},
    },
    {
      now: () => 0,
      sleep: async () => {
        throw new Error('the reset waited on a backend that does not answer');
      },
    },
  );

  assert.equal(cancels, 0, 'nothing is canceled');
  assert.equal(warn.mock.callCount(), 1, 'one warning');
  assert.match(
    String(warn.mock.calls[0]?.arguments[0]),
    /the queue reset stepped aside: the backend does not answer \(connect ECONNREFUSED 127\.0\.0\.1:41234\)/,
  );
});

// Roadmap item 84 (discovered, load): the first wiring read the queue state from an answer that does
// not carry it, and the reset took the resulting error for a backend that does not answer — it
// stepped aside before every spec and the journey still went green. A backend that answers wrongly
// fails the reset with what came back; only a backend that does not answer makes it step aside.
test('a backend that answers wrongly fails the reset instead of making it step aside', async (t) => {
  const warn = t.mock.method(console, 'warn', () => {});
  let cancels = 0;

  const reset = resetQueue(
    {
      snapshot: async () => {
        throw new Error('GET /api/queue answered 200 without a boolean "paused": {"tasks":[]}');
      },
      cancel: async () => {
        cancels += 1;
      },
      resume: async () => {},
    },
    {
      now: () => 0,
      sleep: async () => {
        throw new Error('the reset waited after a wrong answer');
      },
    },
  );

  await assert.rejects(reset, /GET \/api\/queue answered 200 without a boolean "paused"/);
  assert.equal(cancels, 0, 'nothing is canceled');
  assert.equal(warn.mock.callCount(), 0, 'no step-aside warning');
});
