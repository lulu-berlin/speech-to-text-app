import { expect, test } from '@playwright/test';
import { BROKEN2_CLIP, HISTORY_CLIPS, appUrl } from '../lib/backend.ts';
import { appLive, goTo, rowOf, taskIdOf, uploadClips, waitForTranscription } from './helpers/ui';

// This test's own uploads: two trims no other test uses (the shared run finishes the
// common clips before this file runs) plus BROKEN2_CLIP, which fails in CHUNKING. The
// shared suite has finished more tasks by then, so the list's total is not pinned —
// its shape, order and contents are.
const FIRST_CLIP = HISTORY_CLIPS[0]; // 0-7s
const SECOND_CLIP = HISTORY_CLIPS[1]; // 4-11s

test('the history list shows finished tasks newest first with status, finish date and duration', async ({
  page,
}) => {
  // Two completed cards, in upload order: the second finishes after the first.
  await appLive(page);
  await uploadClips(page, [FIRST_CLIP]);
  await waitForTranscription(page, FIRST_CLIP);
  await expect(page.getByText(`${FIRST_CLIP} transcribed`)).toBeVisible();
  await uploadClips(page, [SECOND_CLIP]);
  await waitForTranscription(page, SECOND_CLIP);
  await expect(page.getByText(`${SECOND_CLIP} transcribed`)).toBeVisible();

  // A FAILED card with a stored error.
  await uploadClips(page, [BROKEN2_CLIP]);
  await goTo(page, 'History');
  const failed = rowOf(page, BROKEN2_CLIP);
  await expect(failed.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 60_000 });

  // The whole list, once: the suite's finished tasks plus this test's three uploads.
  // Every card's meta line reads "<Mon> <d>, <yyyy>" — the fixed-UTC date — and
  // carries the duration "mm:ss" (or "h:mm:ss") exactly when the task knows it.
  const items = page.getByRole('listitem');
  await expect(items).not.toHaveCount(0);
  const cards = await items.all();
  const names = await Promise.all(cards.map((card) => card.textContent()));
  for (const card of cards) {
    const meta = card
      .locator('p')
      .filter({ hasText: /, 20\d\d/ })
      .first();
    await expect(meta).toHaveText(/^\w{3} \d{1,2}, \d{4}( · \d{1,2}:\d{2})?$/);
  }

  // Card order is newest first: the ids behind the cards' transcript links, in card
  // order, exactly the backend's finishedAt descending. (A same-again id is fine here:
  // the delete test can reuse rowids; the shape of the order is what is pinned.)
  const cardIds = [];
  for (const card of cards) {
    const href = await card.getByRole('link').first().getAttribute('href');
    cardIds.push(Number(href?.split('/').pop() ?? '0'));
  }
  const taskList: { tasks: { id: number; finishedAt: number | null }[] } = await (
    await page.request.get(appUrl('/api/tasks'))
  ).json();
  const finishedAt: Record<number, number> = Object.fromEntries(
    taskList.tasks
      .filter((task): task is { id: number; finishedAt: number } => task.finishedAt !== null)
      .map((task) => [task.id, task.finishedAt]),
  );
  const times = cardIds.map((id) => finishedAt[id] ?? -1);
  expect(times).toEqual([...times].sort((a, b) => b - a));

  // The FAILED card shows its stored error and no duration.
  const failedId = await taskIdOf(page, BROKEN2_CLIP);
  const stored = await (await page.request.get(appUrl(`/api/tasks/${failedId}`))).json();
  await expect(failed).toContainText(stored.error);
  await expect(
    failed
      .locator('p')
      .filter({ hasText: /, 20\d\d/ })
      .first(),
  ).toHaveText(/^\w{3} \d{1,2}, \d{4}$/);

  // The test's own two cards are completed, and the later-finishing second upload
  // sits above the first (newest first).
  const positionOf = (filename: string) => names.findIndex((text) => text?.includes(filename));
  expect(positionOf(SECOND_CLIP)).toBeGreaterThanOrEqual(0);
  expect(positionOf(SECOND_CLIP)).toBeLessThan(positionOf(FIRST_CLIP));
});
