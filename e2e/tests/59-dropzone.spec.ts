import { expect, test } from '@playwright/test';
import { MASCOT, appLive } from './helpers/ui';

// Item 59, decided header follow-up: the typing animation left the header, and the empty
// queue's drop-zone illustration is where it stays for charm — nothing runs while the
// queue is empty, so the piece is purely decorative. The suite shares one backend and the
// journey's earlier specs finished their tasks, so the queue is idle when this runs.
test('the empty queue’s drop-zone illustration types', async ({ page }) => {
  await appLive(page);
  await expect(
    page.getByText('No tasks yet — drop audio files here, or click to choose them'),
    'the queue is idle for this check',
  ).toBeVisible({ timeout: 60_000 });

  const illustration = page.locator(
    `span:has-text("No tasks yet — drop audio files here, or click to choose them") > ${MASCOT}`,
  );
  await expect(illustration).toBeVisible();
  expect(await illustration.getAttribute('class')).toContain('is-typing');
});
