import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { openTranscript } from './helpers/ui';

// The rename runs on task 2 (jfk-b.wav, the queue journey's second clip: one voice → one
// speaker, default name "Speaker 1"), so task 1 keeps its default name for the transcript
// and download tests.
const NEW_NAME = 'John F. Kennedy';

test('a renamed speaker shows in the transcript, survives a reload, and labels a fresh download', async ({
  page,
}) => {
  await openTranscript(page, 'jfk-b.wav');
  await expect(page).toHaveURL(/\/history\/2$/);
  const nameField = page.getByRole('textbox', { name: 'Name of speaker 1' });
  await expect(nameField).toHaveValue('Speaker 1');

  // Saved on Enter: the turn label follows the stored name.
  await nameField.fill(NEW_NAME);
  await nameField.press('Enter');
  await expect(page.locator(`span:text-is("${NEW_NAME}") + p`)).toHaveCount(1);
  await expect(page.locator('span:text-is("Speaker 1") + p')).toHaveCount(0);

  // Stored on the server, not just in the page: after a reload the field and the turn label
  // still carry the new name.
  await page.reload();
  await expect(nameField).toHaveValue(NEW_NAME);
  await expect(page.locator(`span:text-is("${NEW_NAME}") + p`)).toHaveCount(1);

  // A fresh download is labelled with the stored name (ADR 0034).
  const download = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Download transcript (txt)' }).click();
  const body = await readFile(await (await download).path(), 'utf8');
  expect(body.startsWith(`[${NEW_NAME}]\n`)).toBe(true);
  expect(body).not.toContain('[Speaker 1]');
});
