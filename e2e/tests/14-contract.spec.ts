import { expect, test } from '@playwright/test';
import { CONTRACT_CLIP, appUrl } from '../lib/backend.ts';
import type { HttpCapture } from './helpers/capture';
import { capturedSse, installHttpCapture, installSseCapture } from './helpers/capture';
import { mustConform } from './helpers/contract';
import { appLive, goTo, rowOf, uploadClips, waitForTranscription } from './helpers/ui';

// The docs/api shapes, by destination and method (docs/api/README.md): a task's shape
// is the union of its examples. The upload's `POST /api/tasks` body is one task, while
// `GET /api/tasks` is the task list.
const HTTP_SHAPES: [pattern: RegExp, method: string, status: number, examples: string[]][] = [
  [/\/api\/tasks$/, 'GET', 200, ['task-list']],
  [
    /\/api\/tasks$/,
    'POST',
    201,
    [
      'task.queued',
      'task.running',
      'task.diarizing',
      'task.paused',
      'task.completed',
      'task.failed',
      'task.canceled',
    ],
  ],
  [/\/api\/tasks$/, 'POST', 409, ['error.duplicate-audio']],
  [
    /\/api\/tasks\/\d+$/,
    'GET',
    200,
    [
      'task.queued',
      'task.running',
      'task.diarizing',
      'task.paused',
      'task.completed',
      'task.failed',
      'task.canceled',
    ],
  ],
  [/\/api\/tasks\/\d+\/turns$/, 'GET', 200, ['turns']],
  [/\/api\/tasks\/\d+\/speakers/, 'GET', 200, ['speakers']],
  [/\/api\/queue$/, 'GET', 200, ['queue']],
];
const SSE_EXAMPLES: Record<string, string[]> = {
  snapshot: ['snapshot'],
  'task-updated': [
    'task.queued',
    'task.running',
    'task.diarizing',
    'task.paused',
    'task.completed',
    'task.failed',
    'task.canceled',
  ],
  'task-progress': ['task-progress', 'task-progress.speakers-off'],
  'queue-updated': ['queue'],
  'task-deleted': ['task-deleted'],
  'speakers-renamed': ['speakers-renamed'],
  'speakers-merged': ['speakers-merged'],
  'speakers-reassigned': ['speakers-reassigned'],
  heartbeat: ['heartbeat'],
};

test('the responses the browser received match the structure of their docs/api examples', async ({
  page,
}) => {
  const http: HttpCapture = [];
  installHttpCapture(page, http);
  await installSseCapture(page);

  // A compact journey, so the canvas is wide: upload a fresh clip (unique bytes),
  // watch it complete, exercise the queue pause, then open the transcript (turns)
  // and read the history list. Nothing is intercepted — the capture only listens.
  await appLive(page);
  const clip = CONTRACT_CLIP;
  const pause = page.getByRole('switch', { name: 'Pause the queue' });
  // Pause so the upload's 201 answers while the task is still QUEUED (the engine cannot
  // race the response): the queue-paused snapshot is what task.queued.json declares.
  await pause.click();
  await expect(pause).toBeChecked();
  await uploadClips(page, [clip]);
  await expect(page.getByRole('listitem').filter({ hasText: clip })).toBeVisible();
  await pause.click();
  await expect(pause).not.toBeChecked();
  await waitForTranscription(page, clip);
  await expect(page.getByText(`${clip} transcribed`)).toBeVisible();
  // The duplicate answer (409) exercises the error shape.
  await uploadClips(page, [clip]);
  await expect(page.getByText(/Already uploaded/)).toBeVisible();
  await goTo(page, 'History');
  await rowOf(page, clip).getByRole('link', { name: clip }).click();
  await expect(page.getByRole('heading', { name: clip })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Transcript' })).toBeVisible();

  // Every JSON body the browser received conforms to its docs/api shape...
  await expect.poll(async () => http.length, { timeout: 10_000 }).toBeGreaterThan(0);
  const seenHttp = new Set<string>();
  for (const { url, method, json, status } of http) {
    const examples = HTTP_SHAPES.filter(
      ([, expectedMethod, expectedStatus]) =>
        expectedMethod === method && expectedStatus === status,
    ).find(([pattern]) => pattern.test(url))?.[3];
    if (examples === undefined) continue;
    seenHttp.add(examples.join('|'));
    try {
      mustConform(examples, json);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      throw new Error(`${reason}\n  from ${method} ${url}`);
    }
  }

  // ...and so does every SSE event the stream delivered.
  const sse = await capturedSse(page);
  const seenSse = new Set<string>();
  for (const { name, data } of sse) {
    const examples = SSE_EXAMPLES[name];
    if (examples === undefined) continue;
    seenSse.add(name);
    mustConform(examples, data);
  }

  // The app receives its task list from the snapshot event, so no GET /api/tasks rides
  // the journey; probe it with the browser's own HTTP client to pin the list shape.
  const list = await (await page.request.get(appUrl('/api/tasks'))).json();
  mustConform(['task-list'], list);

  expect([...seenHttp]).toContain('turns');
  expect([...seenSse]).toContain('snapshot');
  expect([...seenSse]).toContain('task-updated');
});
