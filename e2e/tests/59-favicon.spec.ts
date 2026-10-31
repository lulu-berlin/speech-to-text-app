import { expect, test } from '@playwright/test';
import { FAVICON_CLIP } from '../lib/backend.ts';
import { appLive, rowOf, uploadClips, waitForTranscription } from './helpers/ui';

// Item 59, decided header follow-up: the running signal left the header, so the page's
// favicon carries it — the typing artwork while a task runs, the static artwork when the
// queue is idle. The e2e observes the switch (the link's href) and that the running file
// is the animated artwork while the idle file is the still one; a headless browser paints
// no tab icon, so the file's own animation rules stand in for the visible typing.
test('the favicon types while a task runs and is the static icon once the queue is idle', async ({
  page,
}) => {
  await appLive(page);

  const icon = page.locator('link[rel="icon"]');
  const idleHref = await icon.getAttribute('href');
  expect(idleHref, 'the page links a favicon').not.toBeNull();

  // The idle artwork is the still frame: no animation rules at all.
  const idleSvg = await (await page.request.get(new URL(idleHref!, page.url()).href)).text();
  expect(idleSvg).toContain('<svg');
  expect(idleSvg).not.toContain('<style');
  expect(idleSvg).not.toContain('animation');

  // A short clip of this spec's own (the shared backend deduplicates by content): while it
  // transcribes, the page must link the animated artwork.
  await uploadClips(page, [FAVICON_CLIP]);
  await expect(rowOf(page, FAVICON_CLIP).getByText('Transcribing')).toBeVisible({
    timeout: 120_000,
  });
  const typingHref = await icon.getAttribute('href');
  expect(typingHref).not.toBe(idleHref);
  const typingSvg = await (await page.request.get(new URL(typingHref!, page.url()).href)).text();
  expect(typingSvg).toContain('@keyframes');
  expect(typingSvg).toContain('animation');

  // Once the task finishes, no task runs and the favicon is the static artwork again.
  await waitForTranscription(page, FAVICON_CLIP);
  await expect(page.getByText(`${FAVICON_CLIP} transcribed`)).toBeVisible();
  await expect.poll(() => icon.getAttribute('href')).toBe(idleHref);
});
