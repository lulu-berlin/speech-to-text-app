// The journey's queue reset (roadmap item 84, discovered, load): CI run #452 failed two Windows
// specs because earlier specs left clips queued and running, so a spec's own task waited behind
// them. Before each spec every QUEUED, RUNNING and PAUSED task is canceled and a paused queue
// resumed, through the API, and the reset ends on state — once nothing is pending — not on a clock.
import type { TaskSnapshot } from './await-task.ts';
import { stallWatch } from './wait.ts';

/** The backend calls the reset needs, from `GET /api/tasks` and the task and queue commands. */
export interface QueueApi {
  snapshot(): Promise<{ tasks: TaskSnapshot[]; paused: boolean }>;
  cancel(id: number): Promise<void>;
  resume(): Promise<void>;
}

export interface ResetOptions {
  /** Poll interval; the seams below let a unit test drive the loop on a fake clock. */
  intervalMs?: number;
  stallMs?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

/**
 * The backend did not answer at all (a refused or dropped connection), as opposed to answering
 * wrongly; the [QueueApi] throws it so the reset can tell the two apart.
 */
export class BackendUnanswered extends Error {
  override name = 'BackendUnanswered';
}

const PENDING = new Set(['QUEUED', 'RUNNING', 'PAUSED']);

const defaultSleep = (ms: number): Promise<void> => {
  const { promise, resolve } = Promise.withResolvers<void>();
  setTimeout(resolve, ms);
  return promise;
};

/**
 * Cancels every pending task once, resumes a paused queue once, and returns when nothing is pending;
 * fails naming the tasks still pending when nothing changes for [ResetOptions.stallMs], and steps
 * aside with a warning when the backend does not answer.
 */
export async function resetQueue(
  api: QueueApi,
  { intervalMs = 500, stallMs = 600_000, now = Date.now, sleep = defaultSleep }: ResetOptions = {},
): Promise<void> {
  const canceled = new Set<number>();
  let resumed = false;
  // The stall window counts from the last change anywhere in the task list or the queue state, so a
  // slow cancel (a running task the backend is still killing) is not called stuck.
  const unchangedFor = stallWatch(now);
  for (;;) {
    let state: Awaited<ReturnType<QueueApi['snapshot']>>;
    try {
      state = await api.snapshot();
    } catch (error) {
      // A restart spec can leave the backend down; the reset is not the spec's assertion, so it steps
      // aside and the spec fails on its own wait, under its own name. A wrong answer is a real failure.
      if (!(error instanceof BackendUnanswered)) throw error;
      console.warn(`the queue reset stepped aside: the backend does not answer (${error.message})`);
      return;
    }
    const { tasks, paused } = state;
    const stalledFor = unchangedFor(
      `${paused}|${tasks.map((task) => `${task.id}:${task.status}:${task.phase ?? ''}`).join(',')}`,
    );
    if (paused && !resumed) {
      resumed = true;
      await api.resume();
    }
    const pending = tasks.filter((task) => PENDING.has(task.status));
    if (pending.length === 0 && !paused) return;
    if (stalledFor >= stallMs) {
      const stuck = pending.map((task) => `task ${task.id} ${task.filename} ${task.status}`);
      throw new Error(
        `the queue made no progress for ${stallMs} ms while resetting it: ${stuck.join(', ') || 'the queue is still paused'}`,
      );
    }
    for (const task of pending) {
      if (canceled.has(task.id)) continue;
      canceled.add(task.id);
      await api.cancel(task.id);
    }
    await sleep(intervalMs);
  }
}
