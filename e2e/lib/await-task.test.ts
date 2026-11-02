import assert from 'node:assert/strict';
import { test } from 'node:test';
import { awaitTaskState } from './await-task.ts';
import type { TaskSnapshot } from './await-task.ts';

// Roadmap item 84 (discovered): the journey's transcription waits used to expire at 240 s, and on a
// loaded Windows runner a clip that was still transcribing hit that ceiling (CI run #435). The wait
// must follow the task's own progress instead of a clock: it ends when the task does.
test('a transcription still running past 240 s completes while the task keeps progressing', async () => {
  const clip = 'playback.wav';
  let clock = 0;
  const observed: number[] = [];
  const snapshot = async (): Promise<TaskSnapshot[]> => {
    observed.push(clock);
    const running = clock < 300_000;
    return [
      {
        id: 7,
        filename: clip,
        status: running ? 'RUNNING' : 'COMPLETED',
        phase: running ? 'TRANSCRIBING' : null,
        transcriptionProgress: running ? clock / 400_000 : 1,
        diarizationStage: null,
        diarizationProgress: 0,
        error: null,
        finishedAt: null,
      },
    ];
  };
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  const done = await awaitTaskState(snapshot, {
    clip,
    stallMs: 600_000,
    now: () => clock,
    sleep,
  });

  assert.equal(done.status, 'COMPLETED');
  assert.ok(
    observed.some((at) => at > 240_000),
    'the wait was still going after the old 240 s ceiling',
  );
  assert.equal(clock, 300_000, 'the wait ended when the task completed, not before');
});

// Roadmap item 84 (discovered, load): a task that fails must end the wait with the failure, not with
// a locator timeout — the journey must name what went wrong.
test('a task that fails ends the wait naming its status and error', async () => {
  const clip = 'broken.wav';
  let clock = 0;
  const snapshot = async (): Promise<TaskSnapshot[]> => [
    {
      id: 9,
      filename: clip,
      status: 'FAILED',
      phase: null,
      transcriptionProgress: 0.2,
      diarizationStage: null,
      diarizationProgress: 0,
      error: 'ffmpeg failed to encode the upload',
      finishedAt: null,
    },
  ];
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
    if (clock > 60_000) throw new Error('the wait did not end on a failed task');
  };

  await assert.rejects(
    awaitTaskState(snapshot, { clip, stallMs: 600_000, now: () => clock, sleep }),
    /broken\.wav failed: ffmpeg failed to encode the upload/,
  );
});

// Roadmap item 84 (discovered, load): waiting forever would hide a wedged backend until the job's
// timeout. When nothing in the queue changes for the stall window, the wait ends naming the task and
// the progress it reached.
test('a queue that stops progressing ends the wait naming the task and its progress', async () => {
  const clip = 'stuck.wav';
  let clock = 0;
  const snapshot = async (): Promise<TaskSnapshot[]> => [
    {
      id: 11,
      filename: clip,
      status: 'RUNNING',
      phase: 'TRANSCRIBING',
      transcriptionProgress: 0.4,
      diarizationStage: null,
      diarizationProgress: 0,
      error: null,
      finishedAt: null,
    },
  ];
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  await assert.rejects(
    awaitTaskState(snapshot, { clip, stallMs: 600_000, now: () => clock, sleep }),
    /no progress for 600000 ms .*stuck\.wav.*RUNNING TRANSCRIBING 40%/,
  );
});

// Roadmap item 84 (discovered, load): a spec that wants a task's own failed state (59-status-delete's
// FAILED badge) waited on a 120 s locator timeout instead, which a slow queue could exceed. The wait
// follows the task's row until the status the spec wants, rather than to a clock.
test('waiting until FAILED resolves when the task fails instead of rejecting', async () => {
  const clip = 'broken-status.wav';
  let clock = 0;
  const snapshot = async (): Promise<TaskSnapshot[]> => {
    const failed = clock >= 5_000;
    return [
      {
        id: 12,
        filename: clip,
        status: failed ? 'FAILED' : 'RUNNING',
        phase: failed ? null : 'TRANSCRIBING',
        transcriptionProgress: 0,
        diarizationStage: null,
        diarizationProgress: 0,
        error: failed ? 'ffmpeg failed to encode the upload' : null,
        finishedAt: null,
      },
    ];
  };
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  const failed = await awaitTaskState(snapshot, {
    clip,
    until: (task) => task.status === 'FAILED',
    stallMs: 600_000,
    now: () => clock,
    sleep,
  });

  assert.equal(failed.status, 'FAILED');
  assert.match(failed.error ?? '', /ffmpeg failed to encode/);
  assert.ok(clock >= 5_000, 'the wait ended when the task failed');
});

// Roadmap item 84 (discovered, load): a wait for a state the task has already passed would otherwise
// sit out the whole stall window and then blame the queue. It ends at once, naming the task and the
// state it wanted, so a reloaded runner reads a real cause.
test('a wait whose state can no longer arrive ends at once naming it', async () => {
  const clip = 'fast.wav';
  let clock = 0;
  let polls = 0;
  const snapshot = async (): Promise<TaskSnapshot[]> => {
    polls += 1;
    const running = polls === 1;
    return [
      {
        id: 13,
        filename: clip,
        status: running ? 'RUNNING' : 'COMPLETED',
        phase: running ? 'TRANSCRIBING' : null,
        transcriptionProgress: running ? 0.1 : 1,
        diarizationStage: null,
        diarizationProgress: 0,
        error: null,
        finishedAt: null,
      },
    ];
  };
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  await assert.rejects(
    awaitTaskState(snapshot, {
      clip,
      until: (task) => task.phase === 'DIARIZING',
      stallMs: 600_000,
      now: () => clock,
      sleep,
    }),
    /fast\.wav completed before reaching the state the wait wanted/,
  );
  assert.equal(polls, 2, 'the wait ended at the completion, not at the stall window');
});

// Roadmap item 84 (discovered, load): CI run #459's Windows leg failed 10-cancel-retry because the
// wait for a retried task's completion took its first snapshot on the old CANCELED run and ended
// there. The run being replaced is named, so only that run's terminal row is passed over.
test('a wait that replaces a canceled run resolves on the new run', async () => {
  const clip = 'retried.wav';
  let clock = 0;
  const snapshot = async (): Promise<TaskSnapshot[]> => {
    const status =
      clock < 1_000
        ? 'CANCELED'
        : clock < 2_000
          ? 'QUEUED'
          : clock < 3_000
            ? 'RUNNING'
            : 'COMPLETED';
    return [
      {
        id: 21,
        filename: clip,
        status,
        phase: status === 'RUNNING' ? 'TRANSCRIBING' : null,
        transcriptionProgress: status === 'COMPLETED' ? 1 : 0,
        diarizationStage: null,
        diarizationProgress: 0,
        error: null,
        finishedAt: status === 'COMPLETED' ? 5_000 : status === 'CANCELED' ? 1_000 : null,
      },
    ];
  };
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  const done = await awaitTaskState(snapshot, {
    clip,
    after: 1_000,
    stallMs: 600_000,
    now: () => clock,
    sleep,
  });

  assert.equal(done.status, 'COMPLETED');
  assert.equal(done.finishedAt, 5_000, 'the wait ended on the new run, not the canceled one');
});

// The replaced run is passed over even when the caller's predicate matches it: after a retry of an
// already-failed task, `until: FAILED` on the stale row would otherwise return before the retry ran.
test('the run being replaced is passed over even when the predicate matches it', async () => {
  const clip = 'retried-broken.wav';
  let clock = 0;
  const snapshot = async (): Promise<TaskSnapshot[]> => {
    const stale = clock < 2_000;
    return [
      {
        id: 22,
        filename: clip,
        status: stale ? 'FAILED' : 'QUEUED',
        phase: null,
        transcriptionProgress: 0,
        diarizationStage: null,
        diarizationProgress: 0,
        error: stale ? 'the first run failed' : null,
        finishedAt: stale ? 1_000 : null,
      },
    ];
  };
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  const waited = awaitTaskState(snapshot, {
    clip,
    until: (task) => task.status === 'FAILED',
    after: 1_000,
    stallMs: 600_000,
    now: () => clock,
    sleep,
  });
  // Give the loop until the stale run is gone, then stop it: a QUEUED row never satisfies FAILED.
  const outcome = await Promise.race([
    waited.then(() => 'resolved' as const),
    (async () => {
      while (clock < 3_000) await sleep(500);
      return 'still waiting' as const;
    })(),
  ]);

  assert.equal(outcome, 'still waiting', 'the stale FAILED row did not end the wait');
  assert.ok(clock >= 3_000, 'the wait was still going after the replaced run');
});
