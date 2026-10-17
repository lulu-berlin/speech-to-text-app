import { expect, test } from '@playwright/test';
import { BACKEND, delay, launchDesktopApp, processes, survivors } from './helpers/app';

// Roadmap item 50, discovered in the main-tree review (2026-10-15): when the backend has started but
// never answers, the download screen shows the error only after that backend is gone; after Retry, at
// most one backend is ever running, and quitting leaves none. A hung backend is simulated by pausing
// the JVM (SIGSTOP) the moment it appears: it then neither answers /api/health nor reacts to SIGTERM,
// so only the app's stop-and-wait (SIGKILL after 10 s) removes it. The app gives the backend 120 s to
// answer, so the error takes a little over two minutes.
test('a backend that never answers is gone before the error shows, and Retry runs a single backend', async () => {
  const app = await launchDesktopApp();
  let hung: string | undefined;
  try {
    const window = await app.firstWindow();
    hung = await firstBackendPid(60_000);
    process.kill(Number(hung), 'SIGSTOP');

    const retry = window.getByRole('button', { name: 'Retry' });
    await expect(retry).toBeVisible({ timeout: 180_000 });
    expect(backends(), 'the backend that never answered must be gone once the error shows').toEqual(
      [],
    );

    await retry.click();
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
    expect(backends(), 'after Retry exactly one backend runs').toHaveLength(1);
  } finally {
    await app.close();
    // A paused JVM left by a failing run would stop the next spec's survivor checks from passing.
    if (hung && backends().some((line) => line.startsWith(`${hung}:`))) {
      process.kill(Number(hung), 'SIGKILL');
    }
  }
  expect(await survivors(BACKEND, 15_000), 'quitting leaves no backend').toEqual([]);
});

// The running backends, as `pid: command line` lines.
function backends(): string[] {
  return processes(BACKEND).map(({ pid, command }) => `${pid}: ${command}`);
}

// The pid of the first backend the app spawns, polled closely so it is paused before it can answer.
async function firstBackendPid(timeoutMs: number): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const [first] = processes(BACKEND);
    if (first) return first.pid;
    if (Date.now() >= deadline) throw new Error(`no backend appeared within ${timeoutMs / 1000}s`);
    await delay(20);
  }
}
