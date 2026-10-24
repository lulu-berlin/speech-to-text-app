import { expect, test } from '@playwright/test';
import { appUrl, HEADER_CLIP } from '../lib/backend.ts';
import { apiTaskIdOf, ensureTranscript, openTranscript } from './helpers/ui';

// Item 52 (ADR 0046): the whole edit journey against the real backend — a segment's text is
// corrected in the view, the txt download then carries the correction and not whisper's line,
// and Revert (ADR 0061) brings whisper's line back in both.
test('editing a segment reaches the view and the download, and Revert restores the original', async ({
  page,
}) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const region = page.getByRole('region', { name: 'Transcript' });
  const taskId = await apiTaskIdOf(page, HEADER_CLIP);
  const transcript = async () =>
    (await page.request.get(appUrl(`/api/tasks/${taskId}/transcript?format=txt`))).text();

  const before = await transcript();
  const segments = region.locator('[role="button"]:not([aria-label])');
  // The segment to edit: its line occurs exactly once in the download, so removing that line
  // is provable and the assertions below cannot be satisfied by another turn's copy.
  const texts = await segments.allTextContents();
  const original = texts
    .map((text) => text.trim())
    .find((text) => text.length > 0 && before.split(text).length === 2);
  expect(
    original,
    'the transcript has a segment line that occurs once in the download',
  ).toBeDefined();
  const segment = segments.filter({ hasText: original! }).first();
  const edited = 'Corrected by the journey test.';

  // Correct it in the view: click the segment (it becomes current), Edit, type, Save — in the
  // editor's dialog, which is the only thing active while it is open (the user's review
  // 2026-10-23).
  await segment.click();
  await region.getByRole('button', { name: 'Edit segment' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  const field = dialog.getByLabel('Segment text');
  await expect(field).toHaveValue(original!);
  await field.fill(edited);
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(region.getByRole('button', { name: edited })).toBeVisible();

  // The download follows the stored text: the correction is there, whisper's line is gone.
  const after = await transcript();
  expect(after).toContain(edited);
  expect(after).not.toContain(original!);

  // Revert (ADR 0061) puts whisper's line back, in the view and in the download.
  await region.getByRole('button', { name: edited }).click();
  await region.getByRole('button', { name: 'Edit segment' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Revert' }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(region.getByRole('button', { name: original! })).toBeVisible();

  const reverted = await transcript();
  expect(reverted).toContain(original!);
  expect(reverted).not.toContain(edited);
});
