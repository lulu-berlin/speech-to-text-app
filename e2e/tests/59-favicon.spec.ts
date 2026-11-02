import { expect, test } from './helpers/test';
import { FAVICON_CLIP } from '../lib/backend.ts';
import { appLive, uploadClips, waitForTaskState } from './helpers/ui';

// Item 59, decided header follow-up: the running signal left the header, so the page's
// favicon carries it — the typing artwork while a task runs, the static artwork when the
// queue is idle. The e2e observes the switch (the link's href) and that the running file
// is the animated artwork while the idle file is the still one; a headless browser paints
// no tab icon, so the file's own animation rules stand in for the visible typing.
//
// The icon is read from a page-side record, not from an instant: the clip is a short trim by
// design, so on a fast runner the task can finish between the state wait and the read (run
// #481 failed in the same way at 59-task-row). Every DOM change appends the current href, and
// the assertions read that record.
type IconWindow = { hrefs: string[]; observer: MutationObserver };
declare global {
  interface Window {
    faviconHrefs?: IconWindow;
  }
}

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

  await page.evaluate(() => {
    const hrefs: string[] = [];
    const sample = () => {
      const current = document.querySelector('link[rel="icon"]')?.getAttribute('href');
      if (current !== null && current !== undefined && hrefs.at(-1) !== current) {
        hrefs.push(current);
      }
    };
    sample();
    const observer = new MutationObserver(sample);
    observer.observe(document.head, { childList: true, subtree: true, attributes: true });
    window.faviconHrefs = { hrefs, observer };
  });

  // A short clip of this spec's own (the shared backend deduplicates by content): while it
  // transcribes, the page must link the animated artwork.
  await uploadClips(page, [FAVICON_CLIP]);
  await waitForTaskState(page, FAVICON_CLIP);

  const hrefs = await page.evaluate(() => {
    const state = window.faviconHrefs;
    state?.observer.disconnect();
    return state?.hrefs ?? [];
  });
  const typingHref = hrefs.find((href) => href !== idleHref);
  expect(typingHref, 'the favicon switched while the task ran').toBeDefined();
  const typingSvg = await (await page.request.get(new URL(typingHref!, page.url()).href)).text();
  expect(typingSvg).toContain('@keyframes');
  expect(typingSvg).toContain('animation');

  // Once the task finishes, no task runs and the favicon is the static artwork again — the
  // last thing the page linked, and what it links now.
  expect(hrefs.at(-1), 'the favicon ended on the static artwork').toBe(idleHref);
  await expect(page.getByText(`${FAVICON_CLIP} transcribed`)).toBeVisible();
  await expect.poll(() => icon.getAttribute('href')).toBe(idleHref);
});
