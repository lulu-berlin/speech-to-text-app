// Polling for real-world state — a backend answering, a process disappearing — where a fake clock
// cannot see the change. The caller asserts on the result; this only answers whether it happened.

/**
 * The stall rule the task waits share (roadmap item 84): each poll hands the state's signature to
 * the returned function and gets back how long it has gone unchanged. A change anywhere resets the
 * count, so a queue that is slow but moving is never called stuck.
 */
export function stallWatch(now: () => number): (signature: string) => number {
  let seen: string | null = null;
  let since = 0;
  return (signature) => {
    if (signature !== seen) {
      seen = signature;
      since = now();
    }
    return now() - since;
  };
}

export async function waitUntil(
  predicate: () => boolean | Promise<boolean>,
  {
    timeoutMs,
    intervalMs = 100,
    signal,
  }: { timeoutMs: number; intervalMs?: number; signal?: AbortSignal },
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    // Checked before the predicate, so an aborted wait makes no further attempt.
    if (signal?.aborted === true) return false;
    if (await predicate()) return true;
    if (Date.now() >= deadline) return false;
    const { promise, resolve } = Promise.withResolvers<void>();
    setTimeout(resolve, intervalMs);
    await promise;
  }
}
