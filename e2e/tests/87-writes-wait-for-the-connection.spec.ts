import { join } from 'node:path';
import {
  UPLOAD_WAIT_CLIP,
  freezeBackend,
  handOverSharedBackend,
  killFrozenBackend,
  loadState,
  startBackend,
  stopBackend,
} from '../lib/backend.ts';
import { awaitUploadOutcome } from '../lib/upload-outcome.ts';
import { appLive, ensureTranscript, openTranscript, turnTextOf } from './helpers/ui';
import { expect, test } from './helpers/test';

// Item 87: a write is offered only while the app can carry it out, and the reading surface keeps
// working without the service. jfk-b.wav is the rename journey's single-speaker clip, far enough
// from the other specs' names that a standalone run of this file can upload it itself.
const CLIP = 'jfk-b.wav';
const RECONNECTED_NAME = 'Bob after reconnect';

test('writes wait for the connection: the transcript stays readable and a rename goes through after the backend returns', async ({
  page,
}) => {
  await ensureTranscript(page, CLIP);
  await openTranscript(page, CLIP);
  await expect(page.getByText('Live', { exact: true })).toBeVisible();

  const nameField = page.getByRole('textbox', { name: 'Name of speaker 1' });
  const beforeName = await nameField.inputValue();
  await expect(turnTextOf(page, beforeName).first()).toBeVisible();

  // Kill the backend: the stream drops, and the write controls wait for it.
  const { pid, port, dataDir } = loadState();
  await stopBackend(pid, port, dataDir);
  let restarted: { pid: number | undefined } | undefined;
  try {
    await expect(page.getByText('Reconnecting…')).toBeVisible({ timeout: 30_000 });

    // Reading keeps working: the turns are still there and the field still shows the stored
    // name, disabled with the app's reason rather than the browser's.
    await expect(page.getByRole('region', { name: 'Transcript' })).toBeVisible();
    await expect(turnTextOf(page, beforeName).first()).toBeVisible();
    await expect(nameField).toBeDisabled();
    await expect(nameField).toHaveAttribute('title', /Can't reach the app's service/);

    // The queue's write controls carry the same reason.
    await page.getByRole('navigation').getByRole('link', { name: 'Queue' }).click();
    const dropZone = page.getByRole('button', { name: /drop audio files here/i });
    await expect(dropZone).toBeDisabled();
    await expect(dropZone).toHaveAttribute('title', /Can't reach the app's service/);
    const pause = page.getByRole('switch', { name: 'Pause the queue' });
    await expect(pause).toBeDisabled();
    await expect(pause).toHaveAttribute('title', /Can't reach the app's service/);

    // A fresh process on the same socket and data directory brings the connection back.
    restarted = await startBackend({ port, dataDir });
    await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(dropZone).toBeEnabled();

    // The same page, without a reload: the transcript's rename goes through.
    await page.getByRole('navigation').getByRole('link', { name: 'History' }).click();
    await page.getByRole('link', { name: CLIP }).click();
    await expect(page.getByRole('heading', { name: CLIP })).toBeVisible();
    await expect(nameField).toBeEnabled();
    await nameField.fill(RECONNECTED_NAME);
    await nameField.press('Enter');
    await expect(turnTextOf(page, RECONNECTED_NAME)).toHaveCount(1);
    await expect(turnTextOf(page, beforeName)).toHaveCount(0);
  } finally {
    // Whatever happened above, leave a fresh shared backend on the same socket and data
    // directory (the global teardown only knows the run's original pid).
    await handOverSharedBackend(restarted?.pid ?? pid, port, dataDir);
  }
});

test('an upload cut off mid-request waits and finishes after the backend returns', async ({
  page,
}) => {
  test.skip(process.platform === 'win32', 'freezing the backend needs POSIX signals');
  await appLive(page);
  const { pid, port, dataDir, fixturesDir } = loadState();
  if (pid === undefined) {
    throw new Error('the shared backend has no pid');
  }

  // Freeze the backend so the upload's request hangs with no answer; killing it then closes the
  // event stream while the request is still in flight — no response race.
  let restarted: { pid: number | undefined } | undefined;
  let killed = false;
  try {
    freezeBackend(pid);
    // The backend is frozen, so this upload never gets an answer: item 84's uploadClips waits
    // for one 201 per file and would time out here. The row's appearance is the UI signal that
    // the request is in flight — no timer stands in for it.
    await page.setInputFiles('input[type="file"]', join(fixturesDir, UPLOAD_WAIT_CLIP));
    const uploads = page.getByRole('list', { name: 'Uploads' });
    const row = uploads.getByRole('listitem').filter({ hasText: UPLOAD_WAIT_CLIP });
    await expect(row).toBeVisible();

    await killFrozenBackend(pid, port);
    killed = true;
    await expect(page.getByText('Reconnecting…')).toBeVisible({ timeout: 30_000 });

    // The row waits for the connection; it is not an error row.
    await expect(row).toContainText('Waiting for the connection…');
    await expect(row).not.toContainText('Not saved');
    await expect(row).not.toContainText('NetworkError');

    // The same page: the upload finishes once the stream is live. Either the resend lands (the row
    // leaves once the task is in the app's state) or the cut-off attempt had in fact reached the
    // service (the usual duplicate notice names the task). Never a transport error.
    restarted = await startBackend({ port, dataDir });
    await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 60_000 });
    // The one reading of the row decides both: it is gone (the resend landed) or it carries the
    // usual duplicate notice, and neither ever shows the app's transport message. Roadmap item 85
    // (discovered): the row's own text is the state; a fixed 120 s budget was outlasted on a loaded
    // runner (CI run #555) although this state arrived moments later.
    await awaitUploadOutcome(async () => {
      if (!(await row.isVisible())) return null;
      return row.textContent().catch(() => null);
    });
  } finally {
    // A failure before the kill leaves the group SIGSTOPped; SIGTERM cannot end a stopped
    // process, so every later spec would hang. End it here, then hand the shared backend over.
    if (!killed) {
      await killFrozenBackend(pid, port);
    }
    await handOverSharedBackend(restarted?.pid ?? pid, port, dataDir);
  }
});

// Item 95: the Settings page's two frontend fields were the only write controls without item
// 87's gate. With the service frozen they wait with the others, in the app's words.
test('the settings write fields wait for the connection while the service is frozen', async ({
  page,
}) => {
  test.skip(process.platform === 'win32', 'freezing the backend needs POSIX signals');
  await appLive(page);
  await page.getByRole('navigation').getByRole('link', { name: 'Settings' }).click();

  const frontend = page.getByRole('combobox', { name: 'Default frontend' });
  const terminal = page.getByRole('textbox', { name: 'Terminal command' });
  await expect(frontend).toBeEnabled();
  await expect(terminal).toBeEnabled();

  const { pid, port, dataDir } = loadState();
  if (pid === undefined) {
    throw new Error('the shared backend has no pid');
  }
  try {
    // Freezing stops the heartbeat: after the idle window the app is not live, and the two
    // settings fields wait for the service like every other write control.
    freezeBackend(pid);
    await expect(page.getByText('Reconnecting…')).toBeVisible({ timeout: 30_000 });

    await expect(frontend).toBeDisabled();
    await expect(frontend).toHaveAttribute('title', /Can't reach the app's service/);
    await expect(terminal).toBeDisabled();
    await expect(terminal).toHaveAttribute('title', /Can't reach the app's service/);
  } finally {
    // End the frozen group, then hand the shared backend over fresh on the same socket and
    // data directory (a SIGSTOPped group ignores SIGTERM).
    await killFrozenBackend(pid, port);
    await handOverSharedBackend(pid, port, dataDir);
  }
});
