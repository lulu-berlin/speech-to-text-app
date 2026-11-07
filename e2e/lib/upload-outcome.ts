// Waiting for an upload that was cut off mid-request to end (roadmap item 87): after the backend
// returns, the row either leaves (the resend landed) or carries the duplicate notice naming the task
// the cut-off attempt had already created. Roadmap item 85 (discovered): a loaded runner outlasted
// the fixed 120 s budget the spec used to allow (CI run #555), so the wait follows the row's own text
// and ends at once on the app's transport message, which means the write did not wait.

import { defaultSleep, stallWatch } from './wait.ts';

/** How the cut-off upload ended: the resend landed, or the earlier attempt had reached the service. */
export type UploadOutcome = 'landed' | 'duplicate';

export interface AwaitUploadOutcomeOptions {
  /** Poll interval; the seams below let a unit test drive the loop on a fake clock. */
  intervalMs?: number;
  stallMs?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

/** Polls [snapshot] — the uploads row's text, `null` once the row is gone — until the upload ends. */
export async function awaitUploadOutcome(
  snapshot: () => Promise<string | null>,
  {
    intervalMs = 1_000,
    stallMs = 600_000,
    now = Date.now,
    sleep = defaultSleep,
  }: AwaitUploadOutcomeOptions = {},
): Promise<UploadOutcome> {
  const unchangedFor = stallWatch(now);
  for (;;) {
    const text = await snapshot();
    const stalledFor = unchangedFor(text ?? '<gone>');
    if (text === null) return 'landed';
    if (text.includes('Already uploaded')) return 'duplicate';
    // The app's own transport message is the one state this wait must not sit out.
    if (text.includes('Not saved') || text.includes('NetworkError')) {
      throw new Error(`the upload row shows a transport error: ${text.trim()}`);
    }
    if (stalledFor >= stallMs) {
      throw new Error(`the upload row stopped changing for ${stallMs} ms (${text.trim()})`);
    }
    await sleep(intervalMs);
  }
}
