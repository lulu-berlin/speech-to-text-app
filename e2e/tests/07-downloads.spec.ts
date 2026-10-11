import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { JFK_QUOTE, openTranscript } from './helpers/ui';

// What each format must look like for task 1 (jfk-a.wav: one voice → one speaker named
// "Speaker 1" → one turn from 0 ms, under a minute). The formats follow the oracle's
// writers with stored speaker names (ADR 0034): txt is a bracketed speaker header and one
// segment per line; srt and vtt are one cue per turn, labelled "Speaker 1: …". The content
// types come from the transcript route's format table (item 12), the filenames from the
// "stem.extension" rule.
const FORMATS = [
  {
    format: 'txt',
    contentType: /^text\/plain;\s*charset=utf-8$/i,
    shape: /^\[Speaker 1\]\n(?:[^[\n].*\n)+$/,
  },
  {
    format: 'srt',
    contentType: /^application\/x-subrip/,
    shape: /^1\n00:00:00,000 --> 00:00:\d\d,\d{3}\nSpeaker 1: .+\n\n$/,
  },
  {
    format: 'vtt',
    contentType: /^text\/vtt/,
    shape: /^WEBVTT\n\n00:00:00\.000 --> 00:00:\d\d\.\d{3}\nSpeaker 1: .+\n\n$/,
  },
];

test('the txt, srt and vtt downloads have the expected contents and content types', async ({
  page,
}) => {
  await openTranscript(page, 'jfk-a.wav');
  await expect(page).toHaveURL(/\/history\/1$/);

  for (const { format, contentType, shape } of FORMATS) {
    await test.step(format, async () => {
      const response = page.waitForResponse((candidate) =>
        candidate.url().endsWith(`/api/tasks/1/transcript?format=${format}`),
      );
      const download = page.waitForEvent('download');
      await page.getByRole('link', { name: `Download transcript (${format})` }).click();
      expect((await response).headers()['content-type']).toMatch(contentType);
      const file = await download;
      expect(file.suggestedFilename()).toBe(`jfk-a.${format}`);
      const body = await readFile(await file.path(), 'utf8');
      expect(body).toMatch(shape);
      for (const phrase of JFK_QUOTE) {
        expect(body).toMatch(phrase);
      }
    });
  }
});
