import { expect, test } from './helpers/test';
import { HEADER_TYPING_CLIP } from '../lib/backend.ts';
import { appLive, rowOf, uploadClips, waitForTaskState } from './helpers/ui';

// Item 59 (reversed on 2026-10-18): the favicon's animation could not be confirmed at tab
// size, so the 40 px header icon itself plays the typing animation while a task runs and
// rests when none does. The favicon swap stays as the second signal.
test('the header brand icon types while a task runs and rests when none does', async ({ page }) => {
  await appLive(page);

  const brand = page.locator('header svg[viewBox="55 61 293 293"]');
  await expect(brand).toBeVisible();
  await expect(brand, 'nothing runs, so the icon rests').not.toHaveClass(/is-typing/);

  await uploadClips(page, [HEADER_TYPING_CLIP]);
  const row = rowOf(page, HEADER_TYPING_CLIP);
  await waitForTaskState(page, HEADER_TYPING_CLIP, (task) => task.status === 'RUNNING');
  await expect(row.getByText(/Chunking…|Transcribing… \d+%/)).toBeVisible();
  await expect(brand, 'a task runs, so the icon types').toHaveClass(/is-typing/);

  // Cancelling the task (work would be lost, so it confirms) rests the icon again.
  await row.getByRole('button', { name: `Cancel ${HEADER_TYPING_CLIP}` }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Cancel task' }).click();
  await expect(row).toHaveCount(0);
  await expect(brand, 'nothing runs again, so the icon rests').not.toHaveClass(/is-typing/);
});
