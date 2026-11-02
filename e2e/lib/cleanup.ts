// Removing the harness's own temporary directories (roadmap item 84). Windows keeps a handle on a
// stopped backend's data directory for a moment after the port closes, and `rmSync` then throws
// EPERM: a fully green suite must not fail because the OS still had a file open (CI run #475: 55
// passed, 1 skipped, and the run failed in global setup's teardown). The removal is retried while
// the error says the path is busy, and a directory that never goes away is reported, not thrown.
import { rmSync } from 'node:fs';

export interface RemoveOptions {
  /** The removal itself, injectable for a test. */
  remove?: (path: string) => void;
  /** How many times the removal is tried before it is reported as a leftover. */
  attempts?: number;
  /** The pause between attempts. */
  sleep?: (ms: number) => Promise<void>;
  /** The pause for attempt n (1-based), so a test needs no clock. */
  backoffMs?: (attempt: number) => number;
}

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    setTimeout(resolve, ms);
  });

/** Whether [error] says the path could not be removed because something still has it open. */
function busy(error: unknown): boolean {
  const code = (error as { code?: string } | null)?.code;
  return code === 'EPERM' || code === 'EBUSY' || code === 'ENOTEMPTY' || code === 'EACCES';
}

/**
 * Removes [path] recursively, retrying while the OS says it is busy. Resolves `true` when the
 * directory is gone and `false` when it is still there after [RemoveOptions.attempts]; the caller
 * decides what a leftover means (the e2e teardown logs it, since an ephemeral runner's temp
 * directory is not worth failing a green suite for).
 */
export async function removeWithRetry(path: string, options: RemoveOptions = {}): Promise<boolean> {
  const {
    remove = (target) => rmSync(target, { recursive: true, force: true }),
    attempts = 20,
    sleep = defaultSleep,
    backoffMs = () => 500,
  } = options;

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      remove(path);
      return true;
    } catch (error) {
      if (!busy(error) || attempt === attempts) {
        return false;
      }
      await sleep(backoffMs(attempt));
    }
  }
  return false;
}
