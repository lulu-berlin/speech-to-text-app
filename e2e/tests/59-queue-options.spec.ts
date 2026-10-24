import { expect, test } from '@playwright/test';
import { QUEUE_OPTIONS_CLIP, appUrl } from '../lib/backend.ts';
import { appLive, uploadClips } from './helpers/ui';

// Item 59, finding 2: the batch options sat above the drop zone, so nothing grouped them
// with the upload. The queue now leads with the drop zone and keeps the options in an
// "Upload options" disclosure below it; they still apply to the next upload.
test('the queue leads with the drop zone and its upload options still apply to the next upload', async ({
  page,
}) => {
  test.setTimeout(240_000);
  await appLive(page);
  const emptyLabel = 'No tasks yet — drop audio files here, or click to choose them';
  await expect(page.getByText(emptyLabel), 'the queue is idle for this check').toBeVisible({
    timeout: 60_000,
  });

  // Reading order: the drop zone (the button carrying the file input's zone) comes before
  // the upload-options disclosure — and, once that opens, before the first field in it.
  const order = await page.evaluate((text) => {
    const all = [...document.querySelectorAll('body *')];
    const zone = [...document.querySelectorAll('button')].find((button) =>
      button.textContent?.includes(text),
    );
    const disclosure = [...document.querySelectorAll('button')].find(
      (button) => button.textContent === 'Upload options',
    );
    return {
      zone: zone === undefined ? -1 : all.indexOf(zone),
      disclosure: disclosure === undefined ? -1 : all.indexOf(disclosure),
    };
  }, emptyLabel);
  expect(order.zone, 'the drop zone is on the page').toBeGreaterThanOrEqual(0);
  expect(order.disclosure, 'the upload-options control is on the page').toBeGreaterThanOrEqual(0);
  expect(order.zone, 'the drop zone comes before the upload options').toBeLessThan(
    order.disclosure,
  );

  // The options stay reachable from the same view, one disclosure down.
  await page.getByRole('button', { name: 'Upload options' }).click();
  const fields = await page.evaluate((text) => {
    const all = [...document.querySelectorAll('body *')];
    const zone = [...document.querySelectorAll('button')].find((button) =>
      button.textContent?.includes(text),
    );
    const language = document.getElementById('language');
    return {
      zone: zone === undefined ? -1 : all.indexOf(zone),
      language: language === null ? -1 : all.indexOf(language),
    };
  }, emptyLabel);
  expect(fields.language, 'the language field is reachable').toBeGreaterThanOrEqual(0);
  expect(fields.zone, 'the field comes after the drop zone too').toBeLessThan(fields.language);

  await page.getByRole('button', { name: 'Advanced' }).click();
  await page.getByRole('combobox', { name: 'Language' }).click();
  await page.getByRole('option', { name: 'german (de)' }).click();
  await page.getByLabel('Speakers', { exact: true }).selectOption('fixed');
  await page.getByLabel('Speaker count', { exact: true }).fill('2');
  await page.getByLabel('Max speakers', { exact: true }).fill('3');

  // The upload carries the chosen batch options to the task it creates.
  await uploadClips(page, [QUEUE_OPTIONS_CLIP]);
  type CreatedTask = {
    language: string;
    speakersMode: string;
    speakersCount: number;
    maxSpeakers: number;
  };
  let created: CreatedTask | undefined;
  await expect
    .poll(
      async () => {
        const body = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
          tasks: ({ filename: string } & CreatedTask)[];
        };
        created = body.tasks.find((task) => task.filename === QUEUE_OPTIONS_CLIP);
        return created ?? null;
      },
      { timeout: 60_000 },
    )
    .not.toBeNull();
  // The queue sends the chosen language; the task stores its code.
  expect(created?.language).toBe('de');
  expect(created?.speakersMode).toBe('fixed');
  expect(created?.speakersCount).toBe(2);
  expect(created?.maxSpeakers).toBe(3);
});
