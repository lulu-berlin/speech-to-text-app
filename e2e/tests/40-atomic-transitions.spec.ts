import { expect, test } from '@playwright/test';
import { ATOMIC_NEXT_CLIP, ATOMIC_RACE_CLIP } from '../lib/backend.mjs';
import { appLive, goTo, rowOf, uploadClips } from './helpers/ui';

// The race (roadmap item 40): task commands used to be a read-check followed by an
// unconditional write, so a pause landing after the task had finished overwrote the
// terminal status with a dead PAUSED — and because that task had started, the PAUSED
// row is exactly what blocks the queue (the worker waits for a started paused task that
// can never resume). This journey drives pause/resume/cancel in quick succession while
// a long task transcribes, and demands the opposite: the task leaves the queue in
// exactly one consistent state, and the next upload still transcribes to completion.
test('rapid pause/resume/cancel near completion never blocks the queue or sticks a task', async ({
  page,
}) => {
  test.setTimeout(600_000);
  const pauseQueue = page.getByRole('switch', { name: 'Pause the queue' });

  await appLive(page);
  await uploadClips(page, [ATOMIC_RACE_CLIP]);
  const raced = rowOf(page, ATOMIC_RACE_CLIP);
  await expect(raced.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });

  // Pause/resume in quick succession while the worker is mid-run, each confirmed by the
  // row's button flip (the Pause/Resume buttons are mutually exclusive: Resume appears
  // only while the task is paused); then cancel with the confirmation dialog, so the
  // commands race the finishing worker through its chunk boundaries and final write.
  for (let i = 0; i < 3; i++) {
    await raced.getByRole('button', { name: `Pause ${ATOMIC_RACE_CLIP}` }).click();
    await expect(raced.getByRole('button', { name: `Resume ${ATOMIC_RACE_CLIP}` })).toBeVisible();
    await raced.getByRole('button', { name: `Resume ${ATOMIC_RACE_CLIP}` }).click();
    await expect(raced.getByRole('button', { name: `Pause ${ATOMIC_RACE_CLIP}` })).toBeVisible();
  }
  await raced.getByRole('button', { name: `Cancel ${ATOMIC_RACE_CLIP}` }).click();
  await page
    .getByRole('dialog', { name: `Cancel ${ATOMIC_RACE_CLIP}?` })
    .getByRole('button', { name: 'Cancel task' })
    .click();

  // The task leaves the queue: exactly one of completion or cancel won — never a stuck
  // PAUSED — and the task-level commands never touched the queue pause.
  await expect(raced).toHaveCount(0, { timeout: 120_000 });
  await expect(pauseQueue).not.toBeChecked();
  await goTo(page, 'History');
  const racedCard = rowOf(page, ATOMIC_RACE_CLIP);
  await expect(
    racedCard.getByText('COMPLETED', { exact: true }).or(racedCard.getByText('CANCELED', { exact: true })),
  ).toBeVisible();

  // The queue kept working: the follow-up upload still transcribes to completion.
  await goTo(page, 'Queue');
  await uploadClips(page, [ATOMIC_NEXT_CLIP]);
  await expect(rowOf(page, ATOMIC_NEXT_CLIP)).toHaveCount(0, { timeout: 120_000 });
  await goTo(page, 'History');
  await expect(rowOf(page, ATOMIC_NEXT_CLIP).getByText('COMPLETED', { exact: true })).toBeVisible({
    timeout: 120_000,
  });
});