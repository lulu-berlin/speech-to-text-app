// Waiting for a queued task to finish, by the task's own state (roadmap item 84): on a loaded
// runner a transcription can outlast any short ceiling — the Windows job failed at the old 240 s
// wall clock — so the wait ends when the task does, not when a budget is used up. The snapshot
// callback reads the backend's task list; progress and failure handling are added by the cycles
// that prove them.

import { defaultSleep, stallWatch } from './wait.ts';

/** The task fields this wait reads, from `GET /api/tasks`. */
export interface TaskSnapshot {
  id: number;
  filename: string;
  status: string;
  phase: string | null;
  transcriptionProgress: number;
  diarizationStage: string | null;
  diarizationProgress: number;
  error: string | null;
  /** When the run ended (`null` while it has not); the API's own field, `GET /api/tasks`. */
  finishedAt: number | null;
}

export interface AwaitTaskStateOptions {
  /** The upload's filename, as the task list reports it. */
  clip: string;
  /**
   * The row state the wait ends on, for a spec that wants an earlier state than completion (a
   * FAILED badge, a running task's phase). The default is the task's completion.
   */
  until?: (task: TaskSnapshot) => boolean;
  /**
   * The run being replaced (its `finishedAt`): a retried task's old CANCELED or FAILED row is not
   * the end of this wait, so the wait continues until the new run ends.
   */
  after?: number | null;
  /** Poll interval; the seams below let a unit test drive the loop on a fake clock. */
  intervalMs?: number;
  stallMs?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

/** Polls [snapshot] until [until] holds for the task named [clip]; returns that row. */
export async function awaitTaskState(
  snapshot: () => Promise<TaskSnapshot[]>,
  {
    clip,
    until = (task) => task.status === 'COMPLETED',
    after,
    intervalMs = 1_000,
    stallMs = 600_000,
    now = Date.now,
    sleep = defaultSleep,
  }: AwaitTaskStateOptions,
): Promise<TaskSnapshot> {
  const unchangedFor = stallWatch(now);
  for (;;) {
    const tasks = await snapshot();
    const stalledFor = unchangedFor(
      tasks
        .map(
          (candidate) =>
            `${candidate.id}:${candidate.status}:${candidate.phase ?? ''}:${candidate.transcriptionProgress}:${candidate.diarizationStage ?? ''}:${candidate.diarizationProgress}:${candidate.error ?? ''}`,
        )
        .join('|'),
    );
    const task = tasks.find((candidate) => candidate.filename === clip);
    // The run being replaced (a retried task's old CANCELED or FAILED row) is not the end of this
    // wait — not even for a predicate that matches it; the old finish stands while the new run is
    // queued or running.
    const replacingRun =
      after !== undefined && task !== undefined && (task.finishedAt ?? null) === after;
    if (replacingRun) {
      await sleep(intervalMs);
      continue;
    }
    if (task !== undefined && until(task)) return task;
    // The task is done and never reached the wanted state; waiting on would only blame the queue.
    if (task?.status === 'COMPLETED') {
      throw new Error(
        `the task ${clip} completed before reaching the state the wait wanted (${task.status})`,
      );
    }
    if (task !== undefined && (task.status === 'FAILED' || task.status === 'CANCELED')) {
      throw new Error(
        `the task ${clip} ${task.status.toLowerCase()}${task.error === null ? '' : `: ${task.error}`}`,
      );
    }
    if (stalledFor >= stallMs) {
      const state =
        task === undefined
          ? 'no task row yet'
          : `${task.status} ${task.phase ?? ''} ${Math.round(task.transcriptionProgress * 100)}%`;
      throw new Error(
        `the queue made no progress for ${stallMs} ms while waiting for ${clip} (${state.trim()})`,
      );
    }
    await sleep(intervalMs);
  }
}
