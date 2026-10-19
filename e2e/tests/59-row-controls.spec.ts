import { expect, test, type Locator, type Page } from '@playwright/test';
import { ROW_CONTROLS_CLIP, ROW_CONTROLS_SECOND_CLIP, appUrl } from '../lib/backend.mjs';
import { appLive, rowOf, uploadClips } from './helpers/ui';

// An icon-only control's hint: hover it and the tooltip carries the same words as its
// accessible name (item 59); Escape dismisses it before the next control is checked.
async function expectTooltip(page: Page, control: Locator) {
  const name = await control.getAttribute('aria-label');
  expect(name, 'the control has an accessible name').toBeTruthy();
  await control.hover();
  await expect(page.getByRole('tooltip')).toHaveText(name!);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('tooltip')).toBeHidden();
}

// Item 59, finding 6: queue rows carried four same-size icon buttons with no visible
// tooltips, and cancelling a queued task needed no confirmation. Every icon-only control
// now names itself visibly on hover; the controls keep their places when the allowed
// commands change; and cancel still confirms exactly where work would be lost.
test('row controls show tooltips, keep their positions, and cancel confirms only when work would be lost', async ({
  page,
}) => {
  test.setTimeout(300_000);
  await appLive(page);

  // Start from an idle queue: the earlier journeys' uploads may still be finishing.
  await expect
    .poll(
      async () => {
        const body = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
          tasks: { status: string }[];
        };
        return body.tasks.filter((task) => ['QUEUED', 'RUNNING', 'PAUSED'].includes(task.status))
          .length;
      },
      { timeout: 120_000 },
    )
    .toBe(0);

  const queueSwitch = page.getByRole('switch', { name: 'Pause the queue' });
  await queueSwitch.click();
  await expect(queueSwitch).toHaveAttribute('aria-checked', 'true');
  await uploadClips(page, [ROW_CONTROLS_CLIP, ROW_CONTROLS_SECOND_CLIP]);
  const running = rowOf(page, ROW_CONTROLS_CLIP);
  const second = rowOf(page, ROW_CONTROLS_SECOND_CLIP);
  await expect(running.getByText('QUEUED', { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(second.getByText('QUEUED', { exact: true })).toBeVisible();

  // Every icon-only control on a queued row names itself on hover.
  for (const name of [
    `Reorder ${ROW_CONTROLS_CLIP}`,
    `Pause ${ROW_CONTROLS_CLIP}`,
    `Cancel ${ROW_CONTROLS_CLIP}`,
    `Actions for ${ROW_CONTROLS_CLIP}`,
  ]) {
    await expectTooltip(page, running.getByRole('button', { name }));
  }

  // The second row's pause changes which commands are allowed (pause → resume); the
  // controls that stay keep their places.
  const cancel = second.getByRole('button', { name: `Cancel ${ROW_CONTROLS_SECOND_CLIP}` });
  const actions = second.getByRole('button', { name: `Actions for ${ROW_CONTROLS_SECOND_CLIP}` });
  const before = { cancel: await cancel.boundingBox(), actions: await actions.boundingBox() };
  await second.getByRole('button', { name: `Pause ${ROW_CONTROLS_SECOND_CLIP}` }).click();
  await expect(second.getByText('PAUSED', { exact: true })).toBeVisible();
  await expectTooltip(
    page,
    second.getByRole('button', { name: `Resume ${ROW_CONTROLS_SECOND_CLIP}` }),
  );
  const after = { cancel: await cancel.boundingBox(), actions: await actions.boundingBox() };
  expect(after.cancel?.x).toBe(before.cancel?.x);
  expect(after.actions?.x).toBe(before.actions?.x);

  // A queued task never started: cancel is direct, no confirmation.
  await cancel.click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(second).toHaveCount(0);

  // A running task would lose work: cancel asks first, and confirming removes it.
  await queueSwitch.click();
  await expect(queueSwitch).toHaveAttribute('aria-checked', 'false');
  await expect(running.getByText(/Chunking…|Transcribing… \d+%/)).toBeVisible({
    timeout: 120_000,
  });
  await running.getByRole('button', { name: `Cancel ${ROW_CONTROLS_CLIP}` }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText(`Cancel ${ROW_CONTROLS_CLIP}?`);
  await dialog.getByRole('button', { name: 'Cancel task' }).click();
  await expect(running).toHaveCount(0);
});
