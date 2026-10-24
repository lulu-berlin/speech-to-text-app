import { expect, test } from '@playwright/test';
import { HISTORY_BROKEN_CLIP, HISTORY_ROW_CLIP, appUrl } from '../lib/backend.ts';
import { apiTaskIdOf, appLive, goTo, rowOf, uploadClips } from './helpers/ui';

// Item 59, finding 5: the filename was the only way into a completed task and read as
// plain text, while Retry and Delete sat as same-weight text actions. The row's filename
// is now a visibly indicated link the keyboard can follow, Delete reads quieter than Retry
// (muted at rest, danger on hover), and both actions still work.
test('a completed history row opens from a visible link, Delete reads quieter than Retry, and both still work', async ({
  page,
}) => {
  test.setTimeout(300_000);
  await appLive(page);

  // This spec's own completed row.
  await uploadClips(page, [HISTORY_ROW_CLIP]);
  await goTo(page, 'History');
  const completed = rowOf(page, HISTORY_ROW_CLIP);
  await expect(completed.getByText('COMPLETED', { exact: true })).toBeVisible({
    timeout: 240_000,
  });

  // The filename is visibly a link, and the keyboard can follow it into the transcript.
  const link = completed.getByRole('link', { name: HISTORY_ROW_CLIP });
  await expect(link).toBeVisible();
  const decoration = await link.evaluate((element) => getComputedStyle(element).textDecorationLine);
  expect(decoration).toContain('underline');
  await link.focus();
  await expect(link).toBeFocused();
  await link.press('Enter');
  await expect(page.getByRole('heading', { name: HISTORY_ROW_CLIP })).toBeVisible();

  // This spec's own failed row: Retry keeps the accent weight, Delete is muted at rest and
  // turns danger when hovered.
  await goTo(page, 'Queue');
  await uploadClips(page, [HISTORY_BROKEN_CLIP]);
  await goTo(page, 'History');
  const broken = rowOf(page, HISTORY_BROKEN_CLIP);
  await expect(broken.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 120_000 });
  const retry = broken.getByRole('button', { name: 'Retry' });
  const remove = broken.getByRole('button', { name: 'Delete' });
  const colorOf = (locator: typeof retry) =>
    locator.evaluate((element) => getComputedStyle(element).color);
  const [retryColor, deleteRest] = await Promise.all([colorOf(retry), colorOf(remove)]);
  expect(deleteRest).not.toBe(retryColor);
  await remove.hover();
  await expect.poll(() => colorOf(remove)).not.toBe(deleteRest);
  await page.mouse.move(0, 0);

  // Retry still works: the retry request is accepted and the task runs again. The file is
  // undecodable, so the new run fails again at chunking in about a second and never lingers
  // in the queue; its new finish time is what proves the retry ran.
  const taskId = await apiTaskIdOf(page, HISTORY_BROKEN_CLIP);
  type TaskEntry = { id: number; status: string; finishedAt: number | null };
  const taskOf = async (): Promise<TaskEntry | undefined> => {
    const body = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
      tasks: TaskEntry[];
    };
    return body.tasks.find((task) => task.id === taskId);
  };
  const failedAt = (await taskOf())?.finishedAt ?? null;
  expect(failedAt, 'the broken clip failed once already').not.toBeNull();

  const retried = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/tasks/${taskId}/retry`),
  );
  await retry.click();
  expect((await retried).status()).toBe(200);
  await expect
    .poll(
      async () => {
        const task = await taskOf();
        return (
          task?.status === 'FAILED' && task.finishedAt !== null && task.finishedAt !== failedAt
        );
      },
      { timeout: 120_000 },
    )
    .toBe(true);
  await goTo(page, 'History');
  await expect(broken.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 30_000 });

  // Delete still works from the completed row, through its confirmation.
  await goTo(page, 'History');
  await completed.getByRole('button', { name: 'Delete' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Delete task?');
  await dialog.getByRole('button', { name: 'Delete' }).click();
  await expect(completed).toHaveCount(0);
});
