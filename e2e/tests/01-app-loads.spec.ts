import { expect, test } from '@playwright/test';
import { DESKTOP_VIEWPORT, PHONE_VIEWPORT, expectLiveQueue, expectShellAt } from './helpers/ui';

test('starts the real backend and loads the built app at phone and desktop widths', async ({
  page,
}) => {
  for (const [name, viewport] of [
    ['desktop width', DESKTOP_VIEWPORT],
    ['phone width', PHONE_VIEWPORT],
  ] as const) {
    await test.step(name, async () => {
      await page.setViewportSize(viewport);
      await expectShellAt(page);
      await expectLiveQueue(page);
      // Loaded without horizontal overflow at the page level (the layout must fit this
      // width; item 25's geometry tests assert the CSS rules, this checks the result).
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }
});
