import { expect, test } from '@playwright/test';
import { HEADER_TYPING_CLIP, appUrl } from '../lib/backend.mjs';
import { appLive, rowOf, uploadClips } from './helpers/ui';

// Item 59 (reversed on 2026-10-18): the favicon's animation could not be confirmed at tab
// size, so the 40 px header icon itself plays the typing animation while a task runs and
// rests when none does. The favicon swap stays as the second signal.
test('the header brand icon types while a task runs and rests when none does', async ({ page }) => {
  test.setTimeout(300_000);
  await appLive(page);

  // The journey's earlier uploads may still be finishing; start from an idle queue.
  await expect
    .poll(
      async () => {
        const body = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
          tasks: { status: string }[];
        };
        return body.tasks.filter((task) => ['QUEUED', 'RUNNING', 'PAUSED'].includes(task.status))
          .length;
      },
      { timeout: 120_000 },
    )
    .toBe(0);

  const brand = page.locator('header svg[viewBox="55 61 293 293"]');
  await expect(brand).toBeVisible();
  await expect(brand, 'nothing runs, so the icon rests').not.toHaveClass(/is-typing/);

  await uploadClips(page, [HEADER_TYPING_CLIP]);
  const row = rowOf(page, HEADER_TYPING_CLIP);
  await expect(row.getByText(/Chunking…|Transcribing… \d+%/)).toBeVisible({
    timeout: 120_000,
  });
  await expect(brand, 'a task runs, so the icon types').toHaveClass(/is-typing/);

  // Cancelling the task (work would be lost, so it confirms) rests the icon again.
  await row.getByRole('button', { name: `Cancel ${HEADER_TYPING_CLIP}` }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Cancel task' }).click();
  await expect(row).toHaveCount(0);
  await expect(brand, 'nothing runs again, so the icon rests').not.toHaveClass(/is-typing/);
});
