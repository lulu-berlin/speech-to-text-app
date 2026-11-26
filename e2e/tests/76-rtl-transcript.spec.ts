import { readFile } from 'node:fs/promises';
import { expect, test, type Page } from './helpers/test';
import { appUrl, loadState, stopBackend } from '../lib/backend.ts';
import { seedCompletedTask } from '../lib/seed.ts';
import { DESKTOP_VIEWPORT, PHONE_VIEWPORT, startSharedBackend } from './helpers/ui';

// Roadmap item 76 (Decided 2026-10-28): the fixture model is English-only, so the RTL transcript is
// seeded — a completed task's rows inserted into the backend's database before the service starts —
// and its text is asserted exactly. The task's language is English: the direction comes from the
// text, never from the language.
const RTL_FILE = 'محاضرة الأحد.wav';
const SPEAKERS = ['أحمد', 'Speaker 2'];
// An RTL turn with a number inside it, and a mixed turn (Decided 2026-10-28): Latin first, so an
// LTR paragraph with its Arabic run RTL.
const WITH_NUMBER = 'في عام 2024 بدأنا هذا المشروع مع WHO وشركائنا.';
const MIXED = 'WHO: مرحبا بكم';
const TURNS = [
  {
    speakerId: 0,
    startMs: 0,
    endMs: 6_000,
    text: 'مرحبا بكم في البرنامج. نتحدث اليوم عن تاريخ المدينة القديمة وأسواقها ومساجدها وبيوتها التي بقيت قائمة حتى اليوم.',
  },
  { speakerId: 1, startMs: 6_500, endMs: 9_000, text: 'Thanks for having me on the show today.' },
  { speakerId: 0, startMs: 9_500, endMs: 14_000, text: WITH_NUMBER },
  { speakerId: 1, startMs: 14_500, endMs: 16_000, text: MIXED },
];

// The settings write's fields (`PUT /api/settings` takes the settings as a whole).
const SETTINGS_FIELDS = [
  'device',
  'threads',
  'defaultLanguage',
  'speakersMode',
  'speakersCount',
  'maxSpeakers',
  'appLanguage',
  'themeMode',
] as const;

// Stores [appLanguage] (null: the system's) as the app language through the settings API, and
// returns the one stored before.
async function storeAppLanguage(page: Page, appLanguage: string | null): Promise<unknown> {
  const stored = (await (await page.request.get(appUrl('/api/settings'))).json()) as Record<
    string,
    unknown
  >;
  const update = Object.fromEntries(SETTINGS_FIELDS.map((field) => [field, stored[field]]));
  const response = await page.request.put(appUrl('/api/settings'), {
    data: { ...update, appLanguage },
  });
  expect(response.ok(), await response.text()).toBe(true);
  return stored.appLanguage;
}

// Each turn's row, read from the browser: its direction, the text exactly as stored, where its
// time gutter sits against the text (beside it on a desktop, above it on a phone), the side its
// text's first line and its speaker name start on, and the name's own direction.
function readTurnRows(page: Page) {
  return page.evaluate(() =>
    [...document.querySelectorAll('p')]
      .filter((paragraph) => paragraph.querySelector('[role="button"]') !== null)
      .map((paragraph) => {
        let row: HTMLElement | null = paragraph;
        while (row !== null && getComputedStyle(row).display !== 'grid') {
          row = row.parentElement;
        }
        const gutter = row?.firstElementChild?.getBoundingClientRect();
        const text = paragraph.getBoundingClientRect();
        const box = row?.getBoundingClientRect();
        // The text's first line: every box fragment on the topmost line (a bidi run, a segment,
        // a space), joined.
        const range = document.createRange();
        range.selectNodeContents(paragraph);
        const fragments = [...range.getClientRects()].filter((rect) => rect.width > 0);
        const lineTop = Math.min(...fragments.map((rect) => rect.top));
        const firstLine = fragments.filter((rect) => rect.top < lineTop + 2);
        const nameBlock = paragraph.previousElementSibling;
        const name = nameBlock?.querySelector('bdi');
        return {
          direction: row === null ? '' : getComputedStyle(row).direction,
          text: (paragraph.textContent ?? '').replace(/\s+/g, ' ').trim(),
          gutterLeft: gutter?.left ?? 0,
          gutterRight: gutter?.right ?? 0,
          gutterBottom: gutter?.bottom ?? 0,
          rowLeft: box?.left ?? 0,
          rowRight: box?.right ?? 0,
          textLeft: text.left,
          textRight: text.right,
          textTop: text.top,
          lineLeft: Math.min(...firstLine.map((rect) => rect.left)),
          lineRight: Math.max(...firstLine.map((rect) => rect.right)),
          nameBlockLeft: nameBlock?.getBoundingClientRect().left ?? 0,
          nameBlockRight: nameBlock?.getBoundingClientRect().right ?? 0,
          nameLeft: name?.getBoundingClientRect().left ?? 0,
          nameRight: name?.getBoundingClientRect().right ?? 0,
          nameDirection:
            name === null || name === undefined ? '' : getComputedStyle(name).direction,
          clipped: paragraph.scrollWidth > paragraph.clientWidth,
        };
      }),
  );
}

// The on-screen boxes of character ranges in the turn whose text is [text] (one segment, so one
// text node): `[start, end)` offsets into the stored text, each answered with its left and right.
function readTextBoxes(page: Page, text: string, spans: Array<[number, number]>) {
  return page.evaluate(
    ({ turnText, ranges }) => {
      const paragraph = [...document.querySelectorAll('p')].find(
        (candidate) => candidate.textContent?.trim() === turnText,
      );
      const walker = document.createTreeWalker(paragraph ?? document.body, NodeFilter.SHOW_TEXT);
      let node = walker.nextNode();
      while (node !== null && node.textContent !== turnText) {
        node = walker.nextNode();
      }
      return ranges.map(([start, end]) => {
        const range = document.createRange();
        range.setStart(node as Node, start);
        range.setEnd(node as Node, end);
        const { left, right } = range.getBoundingClientRect();
        return { left, right };
      });
    },
    { turnText: text, ranges: spans },
  );
}

// The order of runs inside a paragraph, on screen. The mixed turn: `WHO:` first at the left, the
// Arabic run right of it, and inside the run its first letter right of its last (read right to
// left). The mirror case: a number inside an RTL paragraph keeps its digit order (`2` left of
// `4`), and sits left of the words before it (`في عام`), which read from the right.
async function expectRunOrder(page: Page): Promise<void> {
  const runStart = MIXED.indexOf('م');
  const [who, run, firstArabic, lastArabic] = await readTextBoxes(page, MIXED, [
    [0, 4],
    [runStart, MIXED.length],
    [runStart, runStart + 1],
    [MIXED.length - 1, MIXED.length],
  ]);
  expect(who!.right).toBeLessThanOrEqual(run!.left + 1);
  expect(firstArabic!.left).toBeGreaterThan(lastArabic!.left);

  const year = WITH_NUMBER.indexOf('2024');
  const [two, four, number, before] = await readTextBoxes(page, WITH_NUMBER, [
    [year, year + 1],
    [year + 3, year + 4],
    [year, year + 4],
    [0, year - 1],
  ]);
  expect(two!.left).toBeLessThan(four!.left);
  expect(number!.right).toBeLessThanOrEqual(before!.left + 1);
}

// The transcript page at desktop and phone widths, in an app language whose document direction is
// [documentDir]: the chrome follows the app language, every turn row follows its own text.
async function expectMirroredTranscript(page: Page, documentDir: 'ltr' | 'rtl'): Promise<void> {
  const heading = page.getByRole('heading', { name: RTL_FILE });
  await expect(heading).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('dir', documentDir);

  for (const [name, viewport] of [
    ['desktop width', DESKTOP_VIEWPORT],
    ['phone width', PHONE_VIEWPORT],
  ] as const) {
    await test.step(`${documentDir} app language, ${name}`, async () => {
      await page.setViewportSize(viewport);

      // The file name in its own direction, whatever the app language.
      expect(await heading.evaluate((element) => getComputedStyle(element).direction)).toBe('rtl');

      const rows = await readTurnRows(page);
      expect(rows.map((row) => row.text)).toEqual(TURNS.map((turn) => turn.text));
      expect(rows.map((row) => row.direction)).toEqual(['rtl', 'ltr', 'rtl', 'ltr']);
      // أحمد orders right-to-left, `Speaker 2` left-to-right, wherever their turn puts them.
      expect(rows.map((row) => row.nameDirection)).toEqual(['rtl', 'ltr', 'rtl', 'ltr']);
      for (const row of rows) {
        expect(row.clipped, `"${row.text}" is clipped`).toBe(false);
        expect(row.textLeft).toBeGreaterThanOrEqual(row.rowLeft - 1);
        expect(row.textRight).toBeLessThanOrEqual(row.rowRight + 1);
        // Start-aligned: the text's first line and the speaker name begin at the turn's start side.
        if (row.direction === 'rtl') {
          expect(Math.abs(row.lineRight - row.textRight)).toBeLessThanOrEqual(1);
          expect(Math.abs(row.nameRight - row.nameBlockRight)).toBeLessThanOrEqual(1);
        } else {
          expect(Math.abs(row.lineLeft - row.textLeft)).toBeLessThanOrEqual(1);
          expect(Math.abs(row.nameLeft - row.nameBlockLeft)).toBeLessThanOrEqual(1);
        }
        if (viewport === PHONE_VIEWPORT) {
          // The gutter folds above the text, at the turn's start side.
          expect(row.gutterBottom).toBeLessThanOrEqual(row.textTop);
          if (row.direction === 'rtl') {
            expect(Math.abs(row.gutterRight - row.rowRight)).toBeLessThanOrEqual(1);
          } else {
            expect(Math.abs(row.gutterLeft - row.rowLeft)).toBeLessThanOrEqual(1);
          }
        } else if (row.direction === 'rtl') {
          // The gutter beside the text, at the turn's start side: right of an RTL turn.
          expect(row.gutterLeft).toBeGreaterThanOrEqual(row.textRight);
        } else {
          expect(row.gutterRight).toBeLessThanOrEqual(row.textLeft);
        }
      }

      await expectRunOrder(page);

      // Nothing overflows the page.
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }
}

test('a seeded RTL transcript mirrors per turn at desktop and phone widths, with nothing clipped', async ({
  page,
}) => {
  // Seed before the service starts: stop the shared backend, insert the task, and start it again
  // on the same data directory — in a `finally`, so a failed seed fails this spec alone and not
  // every later one — then carry the new session to this page and the later specs.
  const { pid, port, dataDir } = loadState();
  await stopBackend(pid, port, dataDir);
  let taskId: number;
  try {
    taskId = seedCompletedTask(dataDir, {
      filename: RTL_FILE,
      language: 'en',
      speakers: SPEAKERS,
      turns: TURNS,
    });
  } finally {
    await startSharedBackend(page);
  }

  let storedLanguage: unknown = null;
  try {
    // The app language English: the chrome LTR, the rows by their text.
    storedLanguage = await storeAppLanguage(page, 'en');
    await page.goto(appUrl(`/history/${taskId}`));
    await expectMirroredTranscript(page, 'ltr');

    // The download, end to end (item 76's byte-identical exports): its bytes are the stored turns
    // exactly — no direction marks, isolates or BOM.
    await page.setViewportSize(DESKTOP_VIEWPORT);
    const download = page.waitForEvent('download');
    await page.getByRole('link', { name: 'Download transcript (txt)' }).click();
    const file = await download;
    const expectedTxt = TURNS.map((turn) => `[${SPEAKERS[turn.speakerId]}]\n${turn.text}\n`).join(
      '\n',
    );
    expect(await readFile(await file.path())).toEqual(Buffer.from(expectedTxt, 'utf8'));
    // The file keeps the task's (RTL, non-ASCII) name (discovered): not the URL's `transcript.txt`.
    expect(file.suggestedFilename()).toBe('محاضرة الأحد.txt');

    // The app language Arabic: the chrome mirrors, the rows still follow their text — the English
    // turn keeps its gutter left of its text inside the RTL shell.
    await storeAppLanguage(page, 'ar');
    await page.goto(appUrl(`/history/${taskId}`));
    await expectMirroredTranscript(page, 'rtl');
  } finally {
    // The spec leaves the service as it found it: the seeded task gone, the stored language back.
    await page.request.delete(appUrl(`/api/tasks/${taskId}`));
    await storeAppLanguage(page, typeof storedLanguage === 'string' ? storedLanguage : null);
  }
});
