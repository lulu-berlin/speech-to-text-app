import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import { SHOT_CLIP, SHOT_UPLOAD_CLIP, loadState } from '../lib/backend.mjs';
import { DESKTOP_VIEWPORT, PHONE_VIEWPORT, appLive, goTo, rowOf, uploadClips } from './helpers/ui';

// The acceptance screenshots (roadmap item 21, item 15): the Queue with a running task
// and an upload in flight, History, the transcript during playback, and a canceled
// task's status page — light and dark, phone and desktop — saved under e2e/screenshots/
// for the user's visual approval. The test owns its state: a long seed runs for the
// queue shots and is then canceled; its upload-in-flight mate completes on the freed
// worker and serves as the transcript.
const VIEWPORTS: Record<'phone' | 'desktop', { width: number; height: number }> = {
  phone: PHONE_VIEWPORT,
  desktop: DESKTOP_VIEWPORT,
};

test('captures the acceptance screenshots', async ({ page }) => {
  test.setTimeout(360_000);
  const fixturesDir = loadState().fixturesDir;

  const themeButton = page.getByRole('button', { name: /Theme:/ });
  const setTheme = async (mode: 'light' | 'dark' | 'system') => {
    // The button's accessible name is the current mode (AppHeader THEME_LABEL); the
    // cycle is system -> light -> dark -> system, so clicking until it shows is bounded.
    for (let guard = 0; guard < 4; guard++) {
      if ((await themeButton.getAttribute('aria-label')) === `Theme: ${mode}`) return;
      await themeButton.click();
    }
  };

  const shot = async (name: string, width: 'phone' | 'desktop', theme: 'light' | 'dark') => {
    await page.setViewportSize(VIEWPORTS[width]);
    await setTheme(theme);
    await expect(page.getByRole('button', { name: `Theme: ${theme}` })).toBeVisible();
    await page.screenshot({ path: `screenshots/${name}-${theme}-${width}.png` });
  };

  // A long seed runs while the shots that need a live queue are taken.
  await appLive(page);
  await uploadClips(page, [SHOT_CLIP]);
  const running = rowOf(page, SHOT_CLIP);
  await expect(running.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });
  await expect(running.getByRole('progressbar').first()).toBeVisible();

  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot('queue-running', width, theme);
    }
  }

  // An upload in flight while the seed still runs; it queues behind the seed.
  await page.setInputFiles('input[type="file"]', [join(fixturesDir, SHOT_UPLOAD_CLIP)]);
  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot('queue-uploading', width, theme);
    }
  }

  // Cancel the running seed right away: its card lands in History, the worker frees
  // up, and the queued mate transcribes. Waiting for the mate first would let the
  // ~77 s seed finish on its own and leave no row to cancel.
  await page.setViewportSize(DESKTOP_VIEWPORT);
  await running.getByRole('button', { name: `Cancel ${SHOT_CLIP}` }).click();
  await page
    .getByRole('dialog', { name: `Cancel ${SHOT_CLIP}?` })
    .getByRole('button', { name: 'Cancel task' })
    .click();
  await expect(running).toHaveCount(0);
  await expect(page.getByText(`${SHOT_UPLOAD_CLIP} transcribed`)).toBeVisible({ timeout: 240_000 });

  // History with the canceled card.
  await goTo(page, 'History');
  const canceled = rowOf(page, SHOT_CLIP);
  await expect(canceled.getByText('CANCELED', { exact: true })).toBeVisible();
  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot('history', width, theme);
    }
  }

  // Transcript during playback: open the completed upload-in-flight task and play it.
  await rowOf(page, SHOT_UPLOAD_CLIP).getByRole('link', { name: SHOT_UPLOAD_CLIP }).click();
  await expect(page.getByRole('heading', { name: SHOT_UPLOAD_CLIP })).toBeVisible();
  await page.getByRole('button', { name: 'Play' }).click();
  await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();
  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot('transcript', width, theme);
    }
  }

  // Status page of the canceled task.
  await goTo(page, 'History');
  await canceled.getByRole('link', { name: SHOT_CLIP }).click();
  await expect(page.getByText(/canceled while/i)).toBeVisible();
  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot('status', width, theme);
    }
  }
});
