import { expect, test } from '@playwright/test';
import { STATUS_BROKEN_CLIP } from '../lib/backend.mjs';
import { appLive, goTo, rowOf, uploadClips } from './helpers/ui';

// Item 59, finding 4 extended to the failed/canceled status page: the completed transcript
// demoted Delete behind the actions menu, and the status page follows — Retry stays visible
// (it is the page's purpose), Delete sits in the menu with its confirmation.
test('a failed status page keeps Delete behind the actions menu and Retry working', async ({
  page,
}) => {
  test.setTimeout(300_000);
  await appLive(page);

  await uploadClips(page, [STATUS_BROKEN_CLIP]);
  await goTo(page, 'History');
  const card = rowOf(page, STATUS_BROKEN_CLIP);
  await expect(card.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 120_000 });
  await card.getByRole('link', { name: STATUS_BROKEN_CLIP }).click();
  await expect(page.getByRole('heading', { name: STATUS_BROKEN_CLIP })).toBeVisible();

  // Retry is the page's visible action; Delete is not a bare danger button.
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);

  // Delete is reachable and still asks for confirmation.
  await page.getByRole('button', { name: `Actions for ${STATUS_BROKEN_CLIP}` }).click();
  await page.getByRole('menuitem', { name: 'Delete' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Delete task?');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog).toBeHidden();
  // Retry still works: the retry request is accepted. (The undecodable clip fails again
  // immediately, so the view may show the new failure before any navigation; the status
  // page's return to the queue is covered by its unit test.)
  const taskId = /\/history\/(\d+)$/.exec(page.url())?.[1];
  expect(taskId, 'the status page URL carries the task id').toBeDefined();
  const retried = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/tasks/${taskId}/retry`),
  );
  await page.getByRole('button', { name: 'Retry' }).click();
  expect((await retried).status()).toBe(200);
});
