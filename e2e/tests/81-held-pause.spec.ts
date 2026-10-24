import { expect, test } from '@playwright/test';
import { HELD_PAUSE_CLIP, HELD_PAUSE_NEXT_CLIP } from '../lib/backend.ts';
import { appLive, goTo, rowOf, uploadClips } from './helpers/ui';

// A row command clicked while another is in flight used to be dropped (roadmap item 81): the
// event stream shows a paused task's Resume before the Pause request has answered, and a
// click on it in that window was discarded, leaving a started PAUSED task that blocks the
// queue. The browser holds the Pause response here, so the window lasts as long as the test
// needs: Resume shows (the backend has paused the task and published it) but stays disabled
// until the response arrives; then a click resumes the task, which completes, and the queue
// behind it goes on.
test('a held Pause response keeps Resume disabled; once it arrives, Resume resumes the task and the queue goes on', async ({
  page,
}) => {
  test.setTimeout(600_000);

  await appLive(page);
  await uploadClips(page, [HELD_PAUSE_CLIP, HELD_PAUSE_NEXT_CLIP]);
  const running = rowOf(page, HELD_PAUSE_CLIP);
  const next = rowOf(page, HELD_PAUSE_NEXT_CLIP);
  await expect(running.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });

  // The Pause request reaches the real backend; only its response is held in the browser.
  const answered = Promise.withResolvers<void>();
  const released = Promise.withResolvers<void>();
  await page.route('**/api/tasks/*/pause', async (route) => {
    const response = await route.fetch();
    answered.resolve();
    await released.promise;
    await route.fulfill({ response });
  });

  await running.getByRole('button', { name: `Pause ${HELD_PAUSE_CLIP}` }).click();
  await answered.promise;
  const resume = running.getByRole('button', { name: `Resume ${HELD_PAUSE_CLIP}` });
  await expect(resume).toBeVisible();
  await expect(resume).toBeDisabled();
  await expect(next.getByText('QUEUED', { exact: true })).toBeVisible();

  // The response arrives: Resume is offered, and the click lands.
  released.resolve();
  await expect(resume).toBeEnabled();
  await resume.click();
  await page.unroute('**/api/tasks/*/pause');

  // The resumed task completes, and the one queued behind it starts and completes too.
  await expect(page.getByText(`${HELD_PAUSE_CLIP} transcribed`)).toBeVisible({ timeout: 240_000 });
  await expect(page.getByText(`${HELD_PAUSE_NEXT_CLIP} transcribed`)).toBeVisible({
    timeout: 240_000,
  });
  await expect(running).toHaveCount(0);
  await expect(next).toHaveCount(0);
  await goTo(page, 'History');
  for (const clip of [HELD_PAUSE_CLIP, HELD_PAUSE_NEXT_CLIP]) {
    await expect(rowOf(page, clip).getByText('COMPLETED', { exact: true })).toBeVisible();
  }
});
