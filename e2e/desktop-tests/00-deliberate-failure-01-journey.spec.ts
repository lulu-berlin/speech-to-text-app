import { expect, test } from '@playwright/test';
import { launchDesktopApp } from './helpers/app';

// TEMPORARY (roadmap item 82, part 1): fails on purpose so the run shows whether the packaged
// jobs keep the failure's trace, screenshot and error context. The name contains
// `01-journey.spec.ts` so the jobs that pass that file filter (macOS DMG, Windows) run it too.
// Reverted before the item is reviewed.
test('the packaged app fails on purpose to prove the failure artifacts are kept', async () => {
  const app = await launchDesktopApp();
  try {
    const window = await app.firstWindow();
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
    await expect(
      window.getByRole('heading', { name: 'This heading does not exist (item 82)' }),
    ).toBeVisible({ timeout: 2_000 });
  } finally {
    await app.close();
  }
});
