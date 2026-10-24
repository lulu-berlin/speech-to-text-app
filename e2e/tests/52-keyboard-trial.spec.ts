import { expect, test } from '@playwright/test';
import { HEADER_CLIP } from '../lib/backend.mjs';
import { ensureTranscript, openTranscript } from './helpers/ui';

// Item 52, the user's keyboard trial in a real browser: the player hands focus to the current
// segment, Tab continues from there (never back into the player), Space on a segment plays from
// it and pauses with focus kept, and Escape lands on the current segment.
test('the keyboard walks the transcript: Tab, Space and Escape', async ({ page }) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const player = page.getByRole('group', { name: 'Audio player' });

  const focused = () =>
    page.evaluate(() => {
      const element = document.activeElement as HTMLElement | null;
      return {
        text: element?.textContent?.trim() ?? null,
        ariaLabel: element?.getAttribute('aria-label') ?? null,
        inPlayer: element?.closest('[role="group"][aria-label="Audio player"]') != null,
        current: element?.hasAttribute('data-active') ?? false,
      };
    });
  const playing = () =>
    page.locator('audio').evaluate((audio: HTMLAudioElement) => !audio.paused && !audio.ended);

  // Play pressed in the player: focus goes to the current segment, not the button.
  await player.getByRole('button', { name: 'Play' }).click();
  await expect.poll(focused).toMatchObject({ current: true, inPlayer: false });
  const current = await focused();

  // Tab: the next segment holds the staging focus — the player's controls are not revisited.
  await page.keyboard.press('Tab');
  await expect.poll(focused).toMatchObject({ inPlayer: false });
  const staged = await focused();
  expect(staged.text).toBeTruthy();
  expect(staged.text).not.toBe(current.text);
  expect(staged.ariaLabel).not.toBe('Play segment');
  expect(staged.ariaLabel).not.toBe('Edit segment');

  // Space: playback jumps to the staging segment and runs; a second Space pauses, focus kept.
  await page.keyboard.press('Space');
  await expect.poll(playing).toBe(true);
  await page.keyboard.press('Space');
  await expect.poll(playing).toBe(false);
  expect((await focused()).text).toBe(staged.text);

  // Escape, after the focus has moved off the current segment, lands back on it.
  await page.keyboard.press('Tab');
  await page.keyboard.press('Escape');
  await expect.poll(focused).toMatchObject({ current: true, inPlayer: false });

  // The current segment's controls are keyboard-operable (root review 2026-10-23): Tab reaches
  // Edit and Enter opens the editor with the field focused; Escape closes it and lands back on
  // Edit; Tab reaches Play-segment and Enter plays the segment on its own.
  await page.keyboard.press('Tab');
  await expect.poll(focused).toMatchObject({ ariaLabel: 'Edit segment' });
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('Segment text')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect.poll(focused).toMatchObject({ ariaLabel: 'Edit segment' });
  await page.keyboard.press('Tab');
  await expect.poll(focused).toMatchObject({ ariaLabel: 'Play segment' });
  await page.keyboard.press('Enter');
  await expect.poll(playing).toBe(true);
});
