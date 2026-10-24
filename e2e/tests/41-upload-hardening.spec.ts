import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { UPLOAD_RACE_CLIP, appUrl, loadState } from '../lib/backend.ts';
import { appLive, uploadClips } from './helpers/ui';

// Item 41's upload hardening (ADR 0046): two identical uploads sent at the same time must leave exactly
// one task and answer one 201 and one 409 duplicate-audio naming it — never a 500 — and the queue view
// shows that refusal as the duplicate notice with its link to the winning task.
test('two concurrent identical uploads answer one 201 and one 409, and the queue links to the winner', async ({
  page,
}) => {
  const { fixturesDir } = loadState();
  const bytes = readFileSync(join(fixturesDir, UPLOAD_RACE_CLIP));
  const upload = () =>
    page.request.post(appUrl('/api/tasks'), {
      multipart: {
        file: { name: UPLOAD_RACE_CLIP, mimeType: 'audio/wav', buffer: bytes },
      },
    });

  const [first, second] = await Promise.all([upload(), upload()]);
  const responses = [first, second];
  const statuses = responses.map((response) => response.status()).sort((a, b) => a - b);
  expect(statuses).toEqual([201, 409]);

  const created = responses.find((response) => response.status() === 201);
  const duplicate = responses.find((response) => response.status() === 409);
  if (created === undefined || duplicate === undefined) {
    throw new Error(`expected one 201 and one 409, got ${statuses.join(', ')}`);
  }
  const task = (await created.json()) as { id: number };
  const error = (await duplicate.json()) as { error: string; message: string; taskId: number };
  expect(error.error).toBe('duplicate-audio');
  expect(error.taskId).toBe(task.id);
  expect(error.message).toBe(`This file was already uploaded as task ${task.id}`);

  // One task holds these bytes; the loser stored nothing.
  const list = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
    tasks: { id: number; filename: string }[];
  };
  const matching = list.tasks.filter((item) => item.filename === UPLOAD_RACE_CLIP);
  expect(matching.map((item) => item.id)).toEqual([task.id]);

  // The same file through the queue view is refused with the duplicate notice and its link.
  await appLive(page);
  await uploadClips(page, [UPLOAD_RACE_CLIP]);
  const uploads = page.getByRole('list', { name: 'Uploads' });
  const row = uploads.getByRole('listitem').filter({ hasText: UPLOAD_RACE_CLIP });
  await expect(row.getByText(/Already uploaded/)).toBeVisible();

  const href = await row.getByRole('link', { name: 'open' }).getAttribute('href');
  if (href?.startsWith('/history/')) {
    expect(href).toBe(`/history/${task.id}`);
  } else {
    expect(href).toBe('/queue');
  }
});
