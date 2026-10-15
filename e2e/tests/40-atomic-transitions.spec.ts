import { expect, test } from '@playwright/test';
import { ATOMIC_NEXT_CLIP, ATOMIC_RACE_CLIP } from '../lib/backend.mjs';
import {
  appLive,
  expectCompletedOrCanceled,
  goTo,
  rowOf,
  unlessGone,
  uploadClips,
} from './helpers/ui';

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

  // Pause/resume in quick succession while the worker is mid-run, each confirmed by the row's
  // button flip (the Pause/Resume buttons are mutually exclusive: Resume appears only while the
  // task is paused). A completion may legally win over either command — the row then leaves the
  // queue — so every step runs through `unlessGone`, which accepts a failure only once the row is
  // gone: neither `count()` nor `isVisible()` is atomic against the task finishing, and a pause
  // that wins its write can still lose to the completion (the race item 40 fixed).
  const button = (name: string) => raced.getByRole('button', { name });
  for (let i = 0; i < 3; i++) {
    const pause = button(`Pause ${ATOMIC_RACE_CLIP}`);
    if (!(await unlessGone(raced, () => pause.click({ timeout: 5_000 })))) break;
    const resume = button(`Resume ${ATOMIC_RACE_CLIP}`);
    const resumed = await unlessGone(raced, async () => {
      await expect(resume).toBeVisible({ timeout: 20_000 });
      await resume.click();
    });
    if (!resumed) break;
    const runningAgain = await unlessGone(raced, () =>
      pause.waitFor({ state: 'visible', timeout: 20_000 }),
    );
    if (!runningAgain) break;
  }

  // Cancel with the confirmation dialog, while the task is still in the queue; if the transcription
  // already finished, there is nothing left to cancel. Both clicks run in one guarded step: a completion
  // landing between the click that opens the dialog and the confirmation that closes it takes the row and
  // the dialog with it, which is an outcome, not a failure.
  if (await raced.count()) {
    await unlessGone(raced, async () => {
      await button(`Cancel ${ATOMIC_RACE_CLIP}`).click({ timeout: 5_000 });
      await page
        .getByRole('dialog', { name: `Cancel ${ATOMIC_RACE_CLIP}?` })
        .getByRole('button', { name: 'Cancel task' })
        .click({ timeout: 5_000 });
    });
  }

  // The task leaves the queue: exactly one of completion or cancel won — never a stuck
  // PAUSED — and the task-level commands never touched the queue pause.
  await expect(raced).toHaveCount(0, { timeout: 120_000 });
  await expect(pauseQueue).not.toBeChecked();
  await goTo(page, 'History');
  await expectCompletedOrCanceled(page, ATOMIC_RACE_CLIP);

  // The queue kept working: the follow-up upload still transcribes to completion.
  await goTo(page, 'Queue');
  await uploadClips(page, [ATOMIC_NEXT_CLIP]);
  await expect(rowOf(page, ATOMIC_NEXT_CLIP)).toHaveCount(0, { timeout: 120_000 });
  await goTo(page, 'History');
  await expect(rowOf(page, ATOMIC_NEXT_CLIP).getByText('COMPLETED', { exact: true })).toBeVisible({
    timeout: 120_000,
  });
});
