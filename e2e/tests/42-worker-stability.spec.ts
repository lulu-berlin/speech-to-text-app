import { expect, test } from '@playwright/test';
import { STABILITY_BROKEN_CLIP, STABILITY_CLIP, appUrl } from '../lib/backend.ts';
import { apiTaskIdOf, appLive, goTo, rowOf, uploadClips } from './helpers/ui';

test('a task that fails mid-run does not stop the queue', async ({ page }) => {
  test.setTimeout(360_000);
  await appLive(page);
  await uploadClips(page, [STABILITY_BROKEN_CLIP, STABILITY_CLIP]);

  // Both uploads must have become tasks before leaving the Queue view: an upload still in flight when the
  // view unmounts is cancelled. The suite shares one backend, so the earlier journeys' tasks are listed
  // too; ask only that these two are present, naming any that are not.
  await expect
    .poll(
      async () => {
        const body = await (await page.request.get(appUrl('/api/tasks'))).json();
        const names: string[] = body.tasks.map((task: { filename: string }) => task.filename);
        return [STABILITY_BROKEN_CLIP, STABILITY_CLIP].filter((name) => !names.includes(name));
      },
      { timeout: 60_000 },
    )
    .toEqual([]);

  // The undecodable file fails in CHUNKING: History shows it FAILED with its stored error.
  await goTo(page, 'History');
  const broken = rowOf(page, STABILITY_BROKEN_CLIP);
  await expect(broken.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 60_000 });
  const brokenId = await apiTaskIdOf(page, STABILITY_BROKEN_CLIP);
  const stored = await (await page.request.get(appUrl(`/api/tasks/${brokenId}`))).json();
  expect(stored.error).toBeTruthy();
  await expect(broken).toContainText(stored.error);

  // The task queued behind the failure still runs to completion: the queue survived it.
  const healthy = rowOf(page, STABILITY_CLIP);
  await expect(healthy.getByText('COMPLETED', { exact: true })).toBeVisible({ timeout: 240_000 });
});
