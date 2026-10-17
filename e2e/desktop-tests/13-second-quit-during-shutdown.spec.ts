import { expect, test } from '@playwright/test';
import { once } from 'node:events';
import { APP_PROCESSES, BACKEND, launchDesktopApp, processes, survivors } from './helpers/app';

// Roadmap item 50, discovered in the main-tree review (2026-10-15): a second quit request (SIGTERM)
// while the app waits for a backend that is slow to stop still waits for that stop, so once the app
// has exited nothing it owns is left. The slow stop is a paused JVM (SIGSTOP): it ignores SIGTERM
// until the app's SIGKILL 10 s later. Both SIGTERMs come from inside the main process, a real signal
// through the app's own handler in every package (the bundle's main process is in its own PID
// namespace). The bundle cannot show the orphan: its PID namespace dies with Electron and takes
// the JVM along, so the .deb and the flake package are where this fails without the fix.

test('a second quit request while the backend is stopping still waits for it', async () => {
  const app = await launchDesktopApp();
  const exited = once(app.process(), 'exit');
  let paused: string | undefined;
  try {
    const window = await app.firstWindow();
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
    const backends = processes(BACKEND);
    expect(backends, 'one backend runs before the quit').toHaveLength(1);
    paused = backends[0]!.pid;
    process.kill(Number(paused), 'SIGSTOP');

    await app.evaluate(() => {
      process.kill(process.pid, 'SIGTERM');
      setTimeout(() => process.kill(process.pid, 'SIGTERM'), 1_000);
    });
    await exited;

    expect(await survivors(APP_PROCESSES, 5_000), 'nothing the app owns outlives it').toEqual([]);
  } finally {
    const { exitCode, signalCode } = app.process();
    if (exitCode === null && signalCode === null) await app.close();
    // A paused JVM left by a failing run would stop the next spec's survivor checks from passing.
    if (paused && processes(BACKEND).some(({ pid }) => pid === paused)) {
      process.kill(Number(paused), 'SIGKILL');
    }
  }
});
