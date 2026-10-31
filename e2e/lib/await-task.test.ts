import assert from 'node:assert/strict';
import { test } from 'node:test';
import { awaitTranscription } from './await-task.ts';
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
      },
    ];
  };
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  const done = await awaitTranscription(snapshot, {
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
    },
  ];
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
    if (clock > 60_000) throw new Error('the wait did not end on a failed task');
  };

  await assert.rejects(
    awaitTranscription(snapshot, { clip, stallMs: 600_000, now: () => clock, sleep }),
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
    },
  ];
  const sleep = async (ms: number): Promise<void> => {
    clock += ms;
  };

  await assert.rejects(
    awaitTranscription(snapshot, { clip, stallMs: 600_000, now: () => clock, sleep }),
    /no progress for 600000 ms .*stuck\.wav.*RUNNING TRANSCRIBING 40%/,
  );
});
