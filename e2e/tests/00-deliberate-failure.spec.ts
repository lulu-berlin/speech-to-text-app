import { expect, test } from '@playwright/test';
import { expectShellAt } from './helpers/ui';

// TEMPORARY (roadmap item 82, part 1): fails on purpose so the run shows whether the failure's
// trace, screenshot and error context are kept. Reverted before the item is reviewed.
test('fails on purpose to prove the failure artifacts are kept', async ({ page }) => {
  await expectShellAt(page);
  await expect(
    page.getByRole('heading', { name: 'This heading does not exist (item 82)' }),
  ).toBeVisible({ timeout: 2_000 });
});
