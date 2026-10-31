import { expect, test, type Page } from '@playwright/test';
import { REASSIGN_CLIP } from '../lib/backend.ts';
import { capturedSse, installSseCapture } from './helpers/capture';
import { mustConform } from './helpers/contract';
import { uploadAndOpen } from './helpers/ui';

// The speaker labels of the transcript's turns, in order.
async function turnSpeakers(page: Page): Promise<string[]> {
  return page
    .getByRole('region', { name: 'Transcript' })
    .getByText(/^Speaker \d+$/)
    .allTextContents();
}

// Item 73's amendment (LibriSpeech reader 1089 twice, then reader 1188): its pinned labelling gives
// two speakers, the first with one turn of two segments.
test('reassigns a segment to a new speaker and back, from a turn that holds two segments', async ({
  page,
}) => {
  test.setTimeout(240_000);
  // Record the app's SSE events, so the move's own event is checked against its docs/api example.
  await installSseCapture(page);
  await uploadAndOpen(page, REASSIGN_CLIP);
  const transcript = page.getByRole('region', { name: 'Transcript' });
  const panel = page.getByRole('region', { name: 'Speakers' });
  const segmentBoxes = transcript.getByRole('checkbox', { name: /^Select segment starting / });
  const turnBoxes = transcript.getByRole('checkbox', { name: /^Select turn starting / });
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1', 'Speaker 2']);

  // Move the first turn's second segment to a new speaker: the turn splits around it at once.
  await panel.getByRole('button', { name: 'Reassign speakers' }).click();
  const bar = page.getByRole('region', { name: 'Reassign speakers' });
  await expect(bar).toContainText('Select segments to move to a different speaker');
  await expect(segmentBoxes).toHaveCount(3);
  await segmentBoxes.nth(1).check();
  await expect(bar).toContainText('1 segment selected');
  await bar.getByRole('button', { name: 'Move to…' }).click();
  await bar
    .getByRole('combobox', { name: 'Move to speaker' })
    .selectOption({ label: 'New speaker' });
  await bar.getByRole('button', { name: 'Move', exact: true }).click();
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1', 'Speaker 3', 'Speaker 2']);
  const events = await capturedSse(page);
  const moves = events.filter((event) => event.name === 'speakers-reassigned');
  expect(moves).toHaveLength(1);
  mustConform(['speakers-reassigned'], moves[0]!.data);

  // Move the same segment to the other existing speaker: the turns join.
  await panel.getByRole('button', { name: 'Reassign speakers' }).click();
  await segmentBoxes.nth(1).check();
  await bar.getByRole('button', { name: 'Move to…' }).click();
  await bar.getByRole('combobox', { name: 'Move to speaker' }).selectOption({ label: 'Speaker 2' });
  await bar.getByRole('button', { name: 'Move', exact: true }).click();
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1', 'Speaker 2']);

  // A turn's box ticks every segment of the turn: the second turn holds both of them now.
  await panel.getByRole('button', { name: 'Reassign speakers' }).click();
  await turnBoxes.nth(1).check();
  await expect(bar).toContainText('2 segments selected');
  await expect(segmentBoxes.nth(1)).toBeChecked();
  await expect(segmentBoxes.nth(2)).toBeChecked();

  // Escape leaves the mode; the result is stored and survives a reload.
  await page.keyboard.press('Escape');
  await expect(bar).toHaveCount(0);
  await page.reload();
  await expect.poll(() => turnSpeakers(page)).toEqual(['Speaker 1', 'Speaker 2']);
});
