// Polling for real-world state — a backend answering, a process disappearing — where a fake clock
// cannot see the change. The caller asserts on the result; this only answers whether it happened.

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
