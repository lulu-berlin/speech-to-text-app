import { expect, test } from '@playwright/test';
import { RESTART_CLIPS, loadState, saveState, startBackend, stopBackend } from '../lib/backend.ts';
import { appLive, rowOrder, uploadClips, waitForTranscription } from './helpers/ui';

test('restarting the backend reconnects the event stream and the state is restored from the snapshot', async ({
  page,
}) => {
  // Seed server state that only a fresh process can restore: the queue paused with the
  // clips reordered away from upload order (the engine would otherwise drain them).
  await appLive(page);
  const pause = page.getByRole('switch', { name: 'Pause the queue' });
  await pause.click();
  await expect(pause).toBeChecked();
  await uploadClips(page, RESTART_CLIPS);
  await expect.poll(async () => rowOrder(page, RESTART_CLIPS)).toEqual([0, 1]);
  await page.getByRole('button', { name: `Actions for ${RESTART_CLIPS[0]}` }).click();
  await page.getByRole('menuitem', { name: 'Move down' }).click();
  await expect.poll(async () => rowOrder(page, RESTART_CLIPS)).toEqual([1, 0]);

  // Kill the backend — its app JVM by its args, not only the gradle group (the daemon
  // gradle forks outlives the launcher's group); the browser's stream must notice it.
  const { pid, port, dataDir } = loadState();
  await stopBackend(pid, port, dataDir);
  await expect(page.getByText('Reconnecting…')).toBeVisible({ timeout: 30_000 });

  // Start a fresh backend on the same socket and data directory: the header returns to
  // Live and the first event of the new stream restores the seeded state.
  const restarted = await startBackend({ port, dataDir });
  try {
    await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(pause).toBeChecked();
    await expect.poll(async () => rowOrder(page, RESTART_CLIPS)).toEqual([1, 0]);

    // The restored queue is live: resuming it runs the reordered clips to completion in
    // the new process (so no later test inherits this pause or these QUEUED rows).
    await pause.click();
    await expect(pause).not.toBeChecked();
    for (const clip of RESTART_CLIPS) {
      await waitForTranscription(page, clip);
      await expect(page.getByText(`${clip} transcribed`)).toBeVisible();
    }
  } finally {
    // The global teardown only knows the run's original pid — stop the restarted
    // backend here, or its JVM would outlive this test file. Then bring the shared
    // backend back up on the same socket and data dir for the remaining tests: with
    // `gradle run` the app JVM could outlive the group kill by accident, but the
    // packaged wrapper's JVM dies with its group, so the handover must be explicit.
    await stopBackend(restarted.pid, port, dataDir);
    const fresh = await startBackend({ port, dataDir });
    saveState({ pid: fresh.pid });
  }
});
