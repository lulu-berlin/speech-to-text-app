import { expect, test } from '@playwright/test';
import { METAL_CLIP } from '../lib/backend.ts';
import { JFK_QUOTE, appLive, goTo, uploadAndOpen } from './helpers/ui';

// Roadmap item 48: on the macOS runner (Apple paravirtual Metal), choosing gpu:0 makes the app's
// task run through MTL0. The macOS e2e job greps the captured backend log for `using MTL0
// backend`; this spec proves the choice and the transcript.
test('gpu:0 transcribes through MTL0 and yields the expected transcript', async ({ page }) => {
  test.skip(process.platform !== 'darwin', 'Metal acceleration is a macOS-runner capability');
  test.setTimeout(300_000);

  await appLive(page);
  await goTo(page, 'Settings');
  const device = page.locator('#device');
  await expect(device.locator('option[value="gpu:0"]')).toHaveText('GPU 0 — Metal (MTL0)');
  // The choice saves itself (ADR 0062): no Save button. The task reads the stored device when
  // it starts, so wait until the write reached the backend.
  await device.selectOption('gpu:0');
  await expect(device).toHaveValue('gpu:0');
  await expect
    .poll(
      async () => (await page.evaluate(() => fetch('/api/settings').then((r) => r.json()))).device,
    )
    .toBe('gpu:0');

  try {
    await uploadAndOpen(page, METAL_CLIP);
    for (const phrase of JFK_QUOTE) {
      await expect(page.locator('main')).toContainText(phrase);
    }
  } finally {
    // Leave the shared backend on auto for the rest of the journey.
    await goTo(page, 'Settings');
    await device.selectOption('auto');
    await expect
      .poll(
        async () =>
          (await page.evaluate(() => fetch('/api/settings').then((r) => r.json()))).device,
      )
      .toBe('auto');
  }
});
