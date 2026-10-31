// Waiting for a queued task to finish, by the task's own state (roadmap item 84): on a loaded
// runner a transcription can outlast any short ceiling — the Windows job failed at the old 240 s
// wall clock — so the wait ends when the task does, not when a budget is used up. The snapshot
// callback reads the backend's task list; progress and failure handling are added by the cycles
// that prove them.

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
}

export interface AwaitTranscriptionOptions {
  /** The upload's filename, as the task list reports it. */
  clip: string;
  /** Poll interval; the seams below let a unit test drive the loop on a fake clock. */
  intervalMs?: number;
  stallMs?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number): Promise<void> => {
  const { promise, resolve } = Promise.withResolvers<void>();
  setTimeout(resolve, ms);
  return promise;
};

/** Polls [snapshot] until the task named [clip] completes; returns its final row. */
export async function awaitTranscription(
  snapshot: () => Promise<TaskSnapshot[]>,
  {
    clip,
    intervalMs = 1_000,
    stallMs = 600_000,
    now = Date.now,
    sleep = defaultSleep,
  }: AwaitTranscriptionOptions,
): Promise<TaskSnapshot> {
  let lastChange: number | null = null;
  let signature = '';
  for (;;) {
    const tasks = await snapshot();
    const at = now();
    const next = tasks
      .map(
        (candidate) =>
          `${candidate.id}:${candidate.status}:${candidate.phase ?? ''}:${candidate.transcriptionProgress}:${candidate.diarizationStage ?? ''}:${candidate.diarizationProgress}:${candidate.error ?? ''}`,
      )
      .join('|');
    if (next !== signature || lastChange === null) {
      signature = next;
      lastChange = at;
    }
    const task = tasks.find((candidate) => candidate.filename === clip);
    if (task?.status === 'COMPLETED') return task;
    if (task !== undefined && (task.status === 'FAILED' || task.status === 'CANCELED')) {
      throw new Error(
        `the task ${clip} ${task.status.toLowerCase()}${task.error === null ? '' : `: ${task.error}`}`,
      );
    }
    if (at - lastChange >= stallMs) {
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
