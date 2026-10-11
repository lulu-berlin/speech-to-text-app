import { expect, test } from '@playwright/test';
import { JFK_QUOTE, openTranscript, turnTextOf } from './helpers/ui';

// The journey's first completed clip: item 3 uploaded jfk-a.wav first on this run's fresh
// backend, so it is task 1 — the full 11 s JFK sample, one voice.
test('a completed clip opens its transcript with the expected turn and speaker label', async ({
  page,
}) => {
  await openTranscript(page, 'jfk-a.wav');
  await expect(page).toHaveURL(/\/history\/1$/);

  // The header block: the task's name (openTranscript checks the heading), and its
  // duration on the meta line. Below an hour formatDuration pads minutes and seconds, so
  // the 11 s sample reads "00:11".
  await expect(page.getByText(/· 00:11$/)).toBeVisible();

  // One voice → one speaker → one turn. Speaker 0's default name follows the rule
  // "Speaker {speakerId + 1}", so the turn is labelled "Speaker 1"; its text, right below
  // the label, carries the known quote across the turn's segments.
  const turnText = turnTextOf(page, 'Speaker 1');
  await expect(turnText).toHaveCount(1);
  for (const phrase of JFK_QUOTE) {
    await expect(turnText).toContainText(phrase);
  }

  // The turn starts at the clip's start (whisper's first segment begins at 0 ms), so its
  // gutter reads 00:00 beside it.
  await expect(
    page.locator('span:text-is("00:00") + div').filter({ hasText: /ask not what/i }),
  ).toHaveCount(1);
});
