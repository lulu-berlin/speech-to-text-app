import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { HEADER_CLIP, appUrl } from '../lib/backend.mjs';
import { ensureTranscript, openTranscript, turnTextOf } from './helpers/ui';

const FORMATS = ['txt', 'srt', 'vtt'] as const;

// Item 59, finding 4: the transcript put a large filled red Delete beside quiet export
// text links, so the destructive action was the loudest control on a reading screen. The
// exports keep working from their own control row, Delete moves behind the transcript's
// actions menu (its confirmation unchanged), and a speaker rename still updates the turns.
test('the transcript exports download their bytes, Delete sits behind the actions menu with its confirmation, and rename still works', async ({
  page,
}) => {
  test.setTimeout(300_000);

  // A completed transcript to read (the shared journey already has the header clip
  // completed; a run of this file alone uploads it — re-uploading would answer 409).
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const taskId = /\/history\/(\d+)$/.exec(page.url())?.[1];
  expect(taskId, 'the transcript URL carries the task id').toBeDefined();

  // Each export control still downloads exactly the bytes the transcript route serves.
  for (const format of FORMATS) {
    const download = page.waitForEvent('download');
    await page.getByRole('link', { name: `Download transcript (${format})` }).click();
    const file = await download;
    const downloaded = await readFile(await file.path());
    const response = await page.request.get(
      appUrl(`/api/tasks/${taskId}/transcript?format=${format}`),
    );
    expect(response.ok()).toBe(true);
    expect(downloaded.equals(Buffer.from(await response.body()))).toBe(true);
  }

  // Delete is not the loudest control: no bare danger button on the reading screen. It is
  // reachable from the transcript's actions menu and still asks for confirmation.
  await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: `Actions for ${HEADER_CLIP}` }).click();
  await page.getByRole('menuitem', { name: 'Delete' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Delete task?');
  await expect(dialog.getByRole('button', { name: 'Delete' })).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(dialog).toBeHidden();

  // Renaming a speaker still relabels the turns.
  const nameField = page.getByRole('textbox', { name: 'Name of speaker 1' });
  await nameField.fill('Alice Example');
  await nameField.press('Enter');
  await expect(turnTextOf(page, 'Alice Example')).toHaveCount(1);
  await expect(turnTextOf(page, 'Speaker 1')).toHaveCount(0);
});
