import { expect, test, type Page } from '@playwright/test';
import { MERGE_CLIP } from '../lib/backend.ts';
import { uploadAndOpen } from './helpers/ui';

// The speaker labels of the transcript's turns, in order.
async function turnSpeakers(page: Page): Promise<string[]> {
  return page
    .getByRole('region', { name: 'Transcript' })
    .getByText(/^Speaker \d+$/)
    .allTextContents();
}

// Item 09's two-speaker fixture (LibriSpeech speakers 1089, 1188, 1089 back to back): its
// pinned labelling [0, 0, 1, 1, 0, 0] gives three turns, Speaker 1 → Speaker 2 → Speaker 1.
test('merging two speakers joins their turns, the merge is stored, and "Reset all merges" restores them', async ({
  page,
}) => {
  await uploadAndOpen(page, MERGE_CLIP);
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1', 'Speaker 2', 'Speaker 1']);

  // Merge Speaker 2 into Speaker 1 through the form and its confirmation dialog.
  await page.getByRole('button', { name: 'Merge speakers…' }).click();
  await page.getByRole('checkbox', { name: 'Speaker 1' }).check();
  await page.getByRole('checkbox', { name: 'Speaker 2' }).check();
  await page.getByRole('combobox', { name: 'Keep' }).selectOption({ label: 'Speaker 1' });
  await page.getByRole('button', { name: 'Merge', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Merge speakers?' });
  await expect(dialog).toContainText('Merge Speaker 2 into Speaker 1?');
  await dialog.getByRole('button', { name: 'Merge', exact: true }).click();

  // Every segment now belongs to one speaker, so the three turns join into one; Speaker 2's
  // name field is gone, and the merge can be undone.
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1']);
  await expect(page.getByRole('textbox', { name: 'Name of speaker 2' })).toHaveCount(0);
  const reset = page.getByRole('button', { name: 'Reset all merges' });
  await expect(reset).toBeVisible();

  // Stored on the server: the merge survives a reload.
  await page.reload();
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1']);

  // "Reset all merges" restores both speakers and their three turns.
  await reset.click();
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1', 'Speaker 2', 'Speaker 1']);
  await expect(page.getByRole('textbox', { name: 'Name of speaker 2' })).toHaveValue('Speaker 2');
  await expect(reset).toHaveCount(0);
});
