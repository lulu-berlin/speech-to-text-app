import { expect, test } from '@playwright/test';
import { QUEUE_CLIPS, loadState, startBackend, stopBackend } from '../lib/backend.mjs';
import { appLive, rowOrder, uploadClips } from './helpers/ui';

test('restarting the backend reconnects the event stream and the state is restored from the snapshot', async ({
  page,
}) => {
  test.setTimeout(300_000);

  // Seed server state that only a fresh process can restore: the queue paused with the
  // clips reordered away from upload order (the engine would otherwise drain them).
  await appLive(page);
  const pause = page.getByRole('switch', { name: 'Pause the queue' });
  await pause.click();
  await expect(pause).toBeChecked();
  await uploadClips(page, QUEUE_CLIPS);
  await expect.poll(async () => rowOrder(page, QUEUE_CLIPS)).toEqual([0, 1, 2]);
  await page.getByRole('button', { name: 'Actions for jfk-a.wav' }).click();
  await page.getByRole('menuitem', { name: 'Move down' }).click();
  await expect.poll(async () => rowOrder(page, QUEUE_CLIPS)).toEqual([1, 0, 2]);

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
    await expect.poll(async () => rowOrder(page, QUEUE_CLIPS)).toEqual([1, 0, 2]);

    // The restored queue is live: resuming it runs the reordered clips to completion in
    // the new process (so no later test inherits this pause or these QUEUED rows).
    await pause.click();
    await expect(pause).not.toBeChecked();
    for (const clip of QUEUE_CLIPS) {
      await expect(page.getByText(`${clip} transcribed`)).toBeVisible({ timeout: 240_000 });
    }
  } finally {
    // The global teardown only knows the run's original pid — stop the restarted
    // backend here, or its JVM would outlive this test file.
    await stopBackend(restarted.pid, port, dataDir);
  }
});
