import { expect, test } from '@playwright/test';
import { COMPLETED_PLAYER_CLIP, TRANSCRIBING_BACKGROUND_CLIP, appUrl } from '../lib/backend.ts';
import {
  apiTaskIdOf,
  appLive,
  expectCompletedOrCanceled,
  goTo,
  openTranscript,
  rowOf,
  unlessGone,
  uploadClips,
  waitForTranscription,
} from './helpers/ui';

// Item 37 (ADR 0046): a completed transcript's player used to fetch /audio 500 whenever
// the engine held its single-flight runner with another task — the joiner shared that
// runner — and the player then had no audio, silently (no assertion failed). This journey
// makes the silence the assertion: while a task transcribes, the completed clip's player
// must load actual playable audio.
//
// The file sorts before 17-follow-mode and 17-settings deliberately: 17-settings stops the
// backend it restarted in its finally block, so no test after it has a live backend.
test('a completed player has playable audio while another task transcribes', async ({ page }) => {
  await appLive(page);

  // A short clip completes quickly; its transcript is checked later, while a long clip
  // keeps the engine transcribing.
  await uploadClips(page, [COMPLETED_PLAYER_CLIP]);
  await goTo(page, 'History');
  await waitForTranscription(page, COMPLETED_PLAYER_CLIP);
  await expect(
    rowOf(page, COMPLETED_PLAYER_CLIP).getByText('COMPLETED', { exact: true }),
  ).toBeVisible();
  const completedId = await apiTaskIdOf(page, COMPLETED_PLAYER_CLIP);

  // The long clip (the queue was idle, so it starts right away) keeps the engine in
  // TRANSCRIBING while the completed clip's player is checked; it is cancelled at the end.
  await goTo(page, 'Queue');
  await uploadClips(page, [TRANSCRIBING_BACKGROUND_CLIP]);
  const transcribing = rowOf(page, TRANSCRIBING_BACKGROUND_CLIP);
  await expect(transcribing.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });
  const backgroundId = await apiTaskIdOf(page, TRANSCRIBING_BACKGROUND_CLIP);

  // The item-37 condition, checked, not assumed: the background clip is transcribing at this very
  // moment. The snapshot is taken here — before the player is opened and before its up-to-60 s
  // readyState poll — because the clip can legitimately finish inside that poll, and a later
  // COMPLETED is not a regression.
  const background = await (await page.request.get(appUrl(`/api/tasks/${backgroundId}`))).json();
  expect(background.status).toBe('RUNNING');

  // The completed clip's player, opened mid-transcription: the audio element must load
  // the joined file's metadata (readyState >= 1, HAVE_METADATA — the /audio fetch
  // succeeded and the FLAC header parsed; a 500 leaves readyState 0).
  await openTranscript(page, COMPLETED_PLAYER_CLIP);
  const playerAudio = page.getByLabel(`Audio for ${COMPLETED_PLAYER_CLIP}`);
  await expect
    .poll(() => playerAudio.evaluate((audio: HTMLAudioElement) => audio.readyState), {
      timeout: 60_000,
    })
    .toBeGreaterThanOrEqual(1);

  // The answer is 200 audio/flac — the item-37 regression was the player silently getting no audio
  // while the engine was busy with the background clip.
  const audio = await page.request.get(appUrl(`/api/tasks/${completedId}/audio`));
  expect(audio.status()).toBe(200);
  expect(audio.headers()['content-type']).toContain('audio/flac');

  // Leave the queue clean for the journeys that follow this file (17-follow-mode, then
  // 17-settings): cancel the background task rather than let it transcribe for another
  // minute and delay whatever the next test uploads. The clip may also have finished during
  // the poll above — the row and its dialog then unmount, which is a legal outcome, not a
  // failure. The assertions below still name the states that would be a bug: FAILED, or a
  // task stuck in the queue.
  await goTo(page, 'Queue');
  await unlessGone(transcribing, async () => {
    await page
      .getByRole('button', { name: `Cancel ${TRANSCRIBING_BACKGROUND_CLIP}` })
      .click({ timeout: 5_000 });
    await page
      .getByRole('dialog', { name: `Cancel ${TRANSCRIBING_BACKGROUND_CLIP}?` })
      .getByRole('button', { name: 'Cancel task' })
      .click({ timeout: 5_000 });
  });
  await expect(transcribing).toHaveCount(0, { timeout: 120_000 });
  await goTo(page, 'History');
  await expectCompletedOrCanceled(page, TRANSCRIBING_BACKGROUND_CLIP);
});
