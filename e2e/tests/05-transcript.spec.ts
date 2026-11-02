import { expect, test } from './helpers/test';
import { JFK_QUOTE, apiTaskIdOf, ensureTranscript, openTranscript, turnTextOf } from './helpers/ui';

// The journey's first completed clip: item 3 uploads jfk-a.wav first on this run's fresh backend,
// and a run of this file alone uploads it here — the full 11 s JFK sample, one voice. Its id is
// read from the task list, never assumed.
test('a completed clip opens its transcript with the expected turn and speaker label', async ({
  page,
}) => {
  await ensureTranscript(page, 'jfk-a.wav');
  await openTranscript(page, 'jfk-a.wav');
  await expect(page).toHaveURL(new RegExp(`/history/${await apiTaskIdOf(page, 'jfk-a.wav')}$`));

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
