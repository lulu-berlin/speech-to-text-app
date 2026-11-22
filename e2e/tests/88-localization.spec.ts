import { expect, test } from './helpers/test';
import { appUrl } from '../lib/backend.ts';
import { appLive, goTo, turnTextOf, uploadClips, waitForTaskState } from './helpers/ui';

// The localization journey (roadmap item 88, test-list item 20; ADR 0046): the same
// upload → transcript → speakers → settings path as the English suite, with the app
// language switched to German first, so the whole shell — not one screen — is proven to
// read the catalog. The transcript's text stays English (it is the model's transcription
// of an English clip); the interface around it is German.
test('the German journey: the interface switches and the whole path reads German', async ({
  page,
}) => {
  // A fresh data directory starts in the system's language — English on the runners — but the
  // suite shares one backend, so a run after the Arabic journey starts in Arabic. Set English
  // first through the field's stable id, exactly as the Arabic journey reaches it in any language.
  await appLive(page);
  await page.getByRole('navigation').getByRole('link').last().click();
  await page.locator('#appLanguage').click();
  await page.getByRole('option', { name: 'English (en)' }).click();
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();

  // The App language field switches the whole UI in place: the Settings page, the
  // navigation and the header all read German without a reload.
  const appLanguage = page.getByRole('combobox', { name: 'App language' });
  await appLanguage.click();
  await page.getByRole('option', { name: 'Deutsch (de)' }).click();
  await expect(page.getByRole('heading', { name: 'Einstellungen' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Warteschlange', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Verlauf', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Einstellungen', exact: true })).toBeVisible();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'App-Sprache' })).toHaveValue('Deutsch');

  // The upload: the queue in German, the task accepted and run to completion. The clip may
  // already be finished when this spec runs alone against the shared backend, so the
  // upload happens only when the task list has no completed jfk-a.wav.
  const list = await (await page.request.get(appUrl('/api/tasks'))).json();
  const existing = list.tasks.find(
    (task: { filename: string; status: string }) => task.filename === 'jfk-a.wav',
  );
  if (existing === undefined) {
    await goTo(page, 'Warteschlange');
    await expect(page.getByRole('heading', { name: 'Warteschlange' })).toBeVisible();
    await uploadClips(page, ['jfk-a.wav']);
    await waitForTaskState(page, 'jfk-a.wav');
  }

  // The transcript opens from History (German) by its filename; the turns' speaker labels
  // and the speakers panel are German, while the transcribed text is the model's English.
  await goTo(page, 'Verlauf');
  await expect(page.getByRole('heading', { name: 'Verlauf' })).toBeVisible();
  await page.getByRole('link', { name: 'jfk-a.wav' }).click();
  await expect(page.getByRole('heading', { name: 'jfk-a.wav' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Sprechende' })).toBeVisible();
  const turn = turnTextOf(page, 'Speaker 1');
  await expect(turn).toHaveCount(1);
  await expect(turn).toContainText(/ask not what your country can do for you/i);

  // Back to Settings, in German throughout.
  await goTo(page, 'Einstellungen');
  await expect(page.getByRole('heading', { name: 'Einstellungen' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'App-Sprache' })).toHaveValue('Deutsch');
});

// The RTL journey (roadmap item 88's RTL item): the same path with the app language set to
// Arabic — the document direction flips, the whole shell mirrors and reads Arabic, and the
// stored task data (the filename, the speaker's name, the transcribed text) stays as it is.
test('the Arabic journey: the interface mirrors and reads Arabic, and the path still works', async ({
  page,
}) => {
  await appLive(page);
  // The suite shares one backend with the German journey, which stores its language, so the
  // start language here is unknown: Settings is the navigation's last link, and the App
  // language field's stable id works in any language.
  await page.getByRole('navigation').getByRole('link').last().click();
  const appLanguage = page.locator('#appLanguage');
  await appLanguage.click();
  await page.getByRole('option', { name: 'العربية (ar)' }).click();

  // The document direction follows the app language, so everything inherits RTL.
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.getByRole('heading', { name: 'الإعدادات' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'قائمة الانتظار', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'السجل', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'الإعدادات', exact: true })).toBeVisible();
  await expect(page.getByText('مباشر', { exact: true })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'لغة التطبيق' })).toHaveValue('العربية');

  // The upload and the transcript in Arabic; the clip may already be completed on the
  // shared backend, so the upload happens only when the task list lacks it.
  const list = await (await page.request.get(appUrl('/api/tasks'))).json();
  const existing = list.tasks.find(
    (task: { filename: string; status: string }) => task.filename === 'jfk-a.wav',
  );
  if (existing === undefined) {
    await goTo(page, 'قائمة الانتظار');
    await expect(page.getByRole('heading', { name: 'قائمة الانتظار' })).toBeVisible();
    await uploadClips(page, ['jfk-a.wav']);
    await waitForTaskState(page, 'jfk-a.wav');
  }

  await goTo(page, 'السجل');
  await expect(page.getByRole('heading', { name: 'السجل' })).toBeVisible();
  await page.getByRole('link', { name: 'jfk-a.wav' }).click();
  await expect(page.getByRole('heading', { name: 'jfk-a.wav' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'الأصوات' })).toBeVisible();
  const turn = turnTextOf(page, 'Speaker 1');
  await expect(turn).toHaveCount(1);
  await expect(turn).toContainText(/ask not what your country can do for you/i);

  // The transcript reads correctly inside the RTL shell (item 76): the document stays RTL,
  // and the English turns are still there.
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');

  // Item 88 (user decision 2026-11-13): an English turn keeps its own direction inside the RTL
  // shell — item 76 decides it from the turn's text, so the text is not mirrored.
  await expect
    .poll(() => turn.evaluate((element) => getComputedStyle(element).direction))
    .toBe('ltr');

  await goTo(page, 'الإعدادات');
  await expect(page.getByRole('heading', { name: 'الإعدادات' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'لغة التطبيق' })).toHaveValue('العربية');
});
