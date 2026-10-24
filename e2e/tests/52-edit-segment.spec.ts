import { expect, test, type Page } from '@playwright/test';
import { HEADER_CLIP } from '../lib/backend.ts';
import { ensureTranscript, openTranscript } from './helpers/ui';

// The transcript's segments from the API, in the order the buttons render, with the buttons
// themselves: an index selects a segment however its text repeats. CI transcribes on the CPU and
// can split one line into duplicate segments, so choosing by text was ambiguous there
// (2026-10-24).
async function transcriptSegments(page: Page) {
  const taskId = /\/history\/(\d+)$/.exec(page.url())?.[1];
  expect(taskId, 'the transcript URL carries the task id').toBeDefined();
  const turns: { turns: { segments: { startMs: number; endMs: number; text: string }[] }[] } =
    await (await page.request.get(new URL(`/api/tasks/${taskId}/turns`, page.url()).href)).json();
  return {
    all: turns.turns.flatMap((turn) => turn.segments),
    buttons: page
      .getByRole('region', { name: 'Transcript' })
      .locator('[role="button"]:not([aria-label])'),
  };
}

// Item 52: the current segment's controls sit in the text line. User report 2026-10-23: a
// button box taller than the text raised the line height and shifted the layout; the control
// must be exactly the height of the segment's highlight, its icon inside that box.
test("the current segment's Edit control is exactly the highlight's height, its icon inside it", async ({
  page,
}) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const region = page.getByRole('region', { name: 'Transcript' });
  await region.getByRole('button').first().click();
  const edit = region.getByRole('button', { name: 'Edit segment' });
  await expect(edit).toBeVisible();

  // The segment's highlight on its last line (a segment can wrap) against the control's box.
  const boxes = await edit.evaluate((control) => {
    const segment = control.previousElementSibling;
    const icon = control.querySelector('svg');
    if (segment === null || icon === null) {
      throw new Error('the Edit control follows its segment and holds an icon');
    }
    const lines = segment.getClientRects();
    const highlight = lines[lines.length - 1];
    if (highlight === undefined) {
      throw new Error('the segment has at least one line box');
    }
    const own = control.getBoundingClientRect();
    const glyph = icon.getBoundingClientRect();
    return {
      highlightHeight: highlight.height,
      top: own.top,
      bottom: own.bottom,
      height: own.height,
      iconTop: glyph.top,
      iconBottom: glyph.bottom,
    };
  });
  expect(boxes.height).toBeCloseTo(boxes.highlightHeight, 0);
  expect(boxes.iconTop).toBeGreaterThanOrEqual(boxes.top - 0.5);
  expect(boxes.iconBottom).toBeLessThanOrEqual(boxes.bottom + 0.5);
});

// Item 52, the user's review 2026-10-23: while a segment plays on its own, its Play-segment
// control is a Stop in exactly the same box, so a second click without moving the pointer stops
// it — back at the segment's start, with Play in the same place again. The longest segment is
// used, so it is still playing when the second click lands.
test('the Stop of a segment playing on its own takes the Play control’s box, and a second click there stops it', async ({
  page,
}) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const { all, buttons } = await transcriptSegments(page);
  const longestIndex = all.reduce(
    (best, segment, index, list) =>
      segment.endMs - segment.startMs > list[best]!.endMs - list[best]!.startMs ? index : best,
    0,
  );
  const longest = all[longestIndex]!;
  const region = page.getByRole('region', { name: 'Transcript' });
  const audio = page.locator('audio');

  // Not playing: a click makes the segment current, with its Play-segment control after it.
  await buttons.nth(longestIndex).click();
  const play = region.getByRole('button', { name: 'Play segment' });
  await expect(play).toBeVisible();
  const playBox = await play.boundingBox();
  expect(playBox, 'the Play-segment control has a box').not.toBeNull();
  const x = playBox!.x + playBox!.width / 2;
  const y = playBox!.y + playBox!.height / 2;

  // The first click plays the segment on its own: the Stop takes the very same box.
  await page.mouse.click(x, y);
  const stop = region.getByRole('button', { name: 'Stop segment' });
  await expect(stop).toBeVisible();
  const stopBox = await stop.boundingBox();
  expect(stopBox, 'the Stop has a box').not.toBeNull();
  for (const side of ['x', 'y', 'width', 'height'] as const) {
    expect(Math.abs(stopBox![side] - playBox![side]), side).toBeLessThanOrEqual(0.5);
  }
  await expect(region.getByRole('button', { name: 'Edit segment' })).toHaveAttribute(
    'aria-disabled',
    'true',
  );

  // The second click, without moving, stops it at the segment's start; Play is back in place.
  await page.mouse.click(x, y);
  await expect(play).toBeVisible();
  expect(await audio.evaluate((element: HTMLAudioElement) => element.paused)).toBe(true);
  const seconds = await audio.evaluate((element: HTMLAudioElement) => element.currentTime);
  expect(seconds * 1000).toBeCloseTo(longest.startMs, -1);
  const againBox = await play.boundingBox();
  expect(Math.abs(againBox!.x - playBox!.x)).toBeLessThanOrEqual(0.5);
});

// Item 52, the user's review 2026-10-23: in the dark theme the editor's field showed black text
// on dark gray — a field keeps the browser's own text color unless told otherwise. The field's
// text must be the theme's text color, the one the turn's own text uses.
test("the editor field's text uses the theme's text color in the dark theme", async ({ page }) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);

  // The header's theme button steps through the modes; stop at dark.
  const theme = page.getByRole('button', { name: /^Theme: / });
  for (let step = 0; step < 4; step += 1) {
    if ((await theme.getAttribute('aria-label')) === 'Theme: dark') break;
    await theme.click();
  }
  await expect(page.getByRole('button', { name: 'Theme: dark' })).toBeVisible();

  const region = page.getByRole('region', { name: 'Transcript' });
  await region.getByRole('button').first().click();
  await region.getByRole('button', { name: 'Edit segment' }).click();
  const field = page.getByLabel('Segment text');
  await expect(field).toBeVisible();

  // The page's text carries the theme's text color (the body's own rule); the field must match
  // it, and not the browser's default black. (The edited segment cannot be the reference: as
  // the current one it is highlighted, so its text is the accent's contrast color.)
  const colors = await field.evaluate((element) => ({
    field: getComputedStyle(element).color,
    page: getComputedStyle(document.body).color,
  }));
  expect(colors.page).not.toBe('rgb(0, 0, 0)');
  expect(colors.field).toBe(colors.page);
});

// Item 52, the user's review 2026-10-23: the dialog's Play plays the segment on its own and
// becomes a Stop while it plays; the click never takes the focus from the field, so the user
// keeps typing, and Stop leaves the dialog and the draft open.
test('the editor’s preview plays and stops the segment without taking the field’s focus', async ({
  page,
}) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const region = page.getByRole('region', { name: 'Transcript' });
  await region.getByRole('button').first().click();
  await region.getByRole('button', { name: 'Edit segment' }).click();
  const dialog = page.getByRole('dialog');
  const field = dialog.getByLabel('Segment text');
  const audio = page.locator('audio');

  // A caret in the middle of the text, then Play: the focus and the caret stay where they were.
  await field.evaluate((element) => {
    const textarea = element as HTMLTextAreaElement;
    textarea.focus();
    textarea.setSelectionRange(5, 5);
  });
  await dialog.getByRole('button', { name: 'Play segment' }).click();
  const stop = dialog.getByRole('button', { name: 'Stop segment' });
  await expect(stop).toBeVisible();
  await expect
    .poll(() => audio.evaluate((element: HTMLAudioElement) => !element.paused))
    .toBe(true);
  const kept = await field.evaluate((element) => ({
    focused: document.activeElement === element,
    caret: (element as HTMLTextAreaElement).selectionStart,
    text: (element as HTMLTextAreaElement).value,
  }));
  expect(kept.focused).toBe(true);
  expect(kept.caret).toBe(5);

  await stop.click();
  await expect(dialog.getByRole('button', { name: 'Play segment' })).toBeVisible();
  await expect(dialog).toBeVisible();
  expect(await field.evaluate((element) => (element as HTMLTextAreaElement).value)).toBe(kept.text);
});

// Item 52, the user's review 2026-10-23: the dialog's field wraps a long segment over several
// rows instead of scrolling it sideways, so the whole line can be read while it is edited.
test('the editor’s field wraps a long segment over several rows', async ({ page }) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const { all, buttons } = await transcriptSegments(page);
  const longestIndex = all.reduce(
    (best, segment, index, list) => (segment.text.length > list[best]!.text.length ? index : best),
    0,
  );
  const longest = all[longestIndex]!;
  const region = page.getByRole('region', { name: 'Transcript' });
  await buttons.nth(longestIndex).click();
  await region.getByRole('button', { name: 'Edit segment' }).click();
  const field = page.getByRole('dialog').getByLabel('Segment text');
  await expect(field).toHaveValue(longest.text);

  const box = await field.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      clientHeight: element.clientHeight,
      lines: element.clientHeight / parseFloat(style.lineHeight),
      rows: element.getAttribute('rows'),
    };
  });
  // No sideways scrolling, and room for more than one line of text.
  expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
  expect(box.lines).toBeGreaterThanOrEqual(2.5);
  expect(box.rows).toBe('3');
});

// Item 52, the user's review 2026-10-23: while the editor is open nothing under the shade
// answers — no click starts playback or moves the playhead, and the page does not scroll.
test('the editor’s dialog blocks the page under its shade, and the page does not scroll', async ({
  page,
}) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  const region = page.getByRole('region', { name: 'Transcript' });
  await region.getByRole('button').first().click();
  await region.getByRole('button', { name: 'Edit segment' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  const audio = page.locator('audio');
  const before = await audio.evaluate((element: HTMLAudioElement) => ({
    paused: element.paused,
    time: element.currentTime,
  }));

  // A point inside the transcript but outside the dialog: under the shade.
  const under = await dialog.evaluate((element) => {
    const own = element.getBoundingClientRect();
    const page_ = document.querySelector('main')!.getBoundingClientRect();
    return { x: page_.left + 24, y: own.top + own.height / 2 };
  });
  await page.mouse.click(under.x, under.y);
  await expect(dialog).toBeVisible();
  const after = await audio.evaluate((element: HTMLAudioElement) => ({
    paused: element.paused,
    time: element.currentTime,
  }));
  expect(after).toEqual(before);

  // The page under the shade does not scroll.
  const scrolled = async () =>
    page.evaluate(() => ({
      window: window.scrollY,
      main: document.querySelector('main')!.scrollTop,
    }));
  await page.mouse.move(under.x, under.y);
  await page.mouse.wheel(0, 600);
  await page.waitForTimeout(200);
  expect(await scrolled()).toEqual({ window: 0, main: 0 });
});
