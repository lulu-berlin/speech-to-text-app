# 88 — Localization: the app's own language, in every language whisper transcription supports

Status: done (roadmap item 88, approved 2026-11-15)
Depends on: [74](desktop-presentation.md) (font coverage for every script, done)
Blocks: —

## Goal

The app's interface — every label, message, button, status and error — reads naturally in each of
the languages whisper transcription supports, in gender-neutral and culturally sensitive wording,
with terminology that matches what established products use; and the whole of it stays correct as
the app grows.

## Evidence

- **The language set is whisper's**: `docs/api/languages.json` lists whisper's 100 language codes
  plus `auto` (`en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu
  ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl
  mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw
  ln ha ba jw su yue`). Seven are right-to-left: `ar he fa ur sd ps yi`.
- **There is no localization layer**: no i18n library in `frontend/package.json`; strings sit inline
  in components and in `aria-label`s, and the transcript view prints the server's English message
  for a rejected action (`SpeakersPanel.tsx:200` and its neighbours).
- **The e2e journey names English strings**: `e2e/tests` holds 317 `getByRole`/`getByText`/
  `getByLabel` selectors with English names, so the journey has to pin one locale, not follow the
  system.
- **Scripts and direction are already solved for the transcript, not for the UI**: item 74 shipped
  font coverage for every language and Japanese glyph forms; item 76 gives the transcript its own
  `lang`, so a Japanese transcript in an English UI reads correctly. Neither covers the app's own
  text being in Arabic, Hebrew, Persian, Urdu, Sindhi, Pashto or Yiddish, where the layout itself
  must mirror.
- **The app already has a "language" that is not the UI's**: the upload form's language is the
  transcription language (`docs/api/languages.json`), so an "app language" is a second, separate
  setting and must be named so no one confuses them.
- **The terminology has established counterparts**: large companies' products name the same objects
  — transcript, speaker, merge, upload, queue, cancel, retry, download, settings — in their own
  localized UI (Microsoft Word/Teams, Google Docs/Recorder, Apple Voice Memos, Zoom, plus
  transcription products such as Otter.ai and Descript), and those are the references this item
  measures against.

## Decided (consultation, 2026-10-31)

- **All of whisper's languages** (the user): the app is translated into every language whisper
  transcription supports.
- **Quality over speed** (the user): terminology is compared against other apps that do similar
  things, ideally popular ones backed by large companies.
- **Gender-neutral wording, culturally sensitive** (the user): wherever the language allows it, and
  never at the cost of sounding unnatural.
- **A first wave, then the rest** (the user), and the wave goes **by the number of first-language
  speakers in the world**, **all the way up to Italian**: every whisper language at or above
  Italian's count ships first, and each next language is added by the same process.
- **Regional variants in the UI** (the user): `zh` split into Simplified and Traditional, `pt` into
  Brazilian and European, `es` into Spain and Latin American; one Arabic UI locale, because the
  transcription code `ar` covers dialectal speech (user decision 2026-11-12); a mapping connects a
  UI locale to the transcription code whisper receives.
- **Review** (the user): the agent drafts each locale and a second, independent agent pass reviews it
  against the written rubric; a native-speaker check is best effort — it is never a requirement —
  and the standing evidence for a wording is that an established product with a large user base
  uses it, recorded per term in the locale's review record.
- **Translations are browsable and open to changes** (the user): every locale's strings are readable
  in the repository without running the app, and anyone can propose a change through a pull request
  that the usual checks and the review vet; the screenshots stay a CI artifact, not committed.
- **formatjs / react-intl** (the user): ICU messages, plurals and `select`, `Intl` formats.
- **Errors by code** (the user): the app translates a refusal from its error code; the server keeps
  English messages and their codes for now, and the server's text is the fallback for a code the
  catalog does not know. Server-side localization may come later.
- **Right-to-left in the same item** (the user) for `ar he fa ur sd ps yi`.
- **After 1.0** (the user): not a gate of item 78.
- **The setting is stored in the database** (the user), defaulted from the system's language and
  switched on the Settings page.

## Proposal

1. **A localization layer**: one i18n library, one message catalog per locale, an "App language"
   setting (default from the system's language, switchable on the Settings page) kept apart from the
   transcription language, and every string — labels, `aria-label`s, tooltips, status words, error
   messages, dates, numbers, plurals — read from the catalog.
2. **A terminology glossary and a style guide per language family**, written from a comparison of
   the reference products above: the words for transcript, speaker, turn, merge, queue, upload,
   file, task, cancel, retry, download, settings; date, time and number formats; how the app
   addresses the user (neutral forms, no gendered participles addressing the person); plural rules;
   right-to-left layout; what is never translated (product name, file extensions, model names).
3. **A translation and review process per locale**: a first draft against the glossary, an
   independent review against a written rubric (meaning, tone, gender neutrality, cultural fit,
   placeholder integrity, length), and a review record per locale holding, for each term that
   matters, the products compared and the wording they use — that comparison is the evidence a
   wording is acceptable; a native-speaker opinion is recorded when one is offered, as best effort.
4. **Server messages by code**: the API keeps its English messages, and the app shows the message
   its catalog holds for the error code, falling back to the server's text for codes it does not
   know — so a localized UI is not half-English when an action is refused.
5. **A quality gate that keeps it true**: the shipped catalog must have no missing keys, no stray
   hard-coded strings, intact placeholders and ICU syntax, correct plural categories per language;
   a docs/CI check enforces these, and the per-locale screenshots are a CI artifact for visual
   review.
6. **Right-to-left layout** for `ar he fa ur sd ps yi`: mirrored layout, logical CSS properties,
   direction-aware icons, and a journey in one RTL locale.
7. **Waves ordered by first-language speakers**: the ranking is Ethnologue's 2026 first-language
   counts as of 2026-10-31 (recorded in this item's log), restricted to whisper's codes; the first
   wave is every language at or above Italian's count, and each later language enters by the same
   steps (catalog, glossary, review record, review page) without touching another locale's.
8. **A browsable translation review page per locale**, committed and generated from the catalog:
   each string with its source text and its translation, the glossary terms it uses and the products
   behind them, so anyone can read a language's wording, judge it and propose a change. `CONTRIBUTING.md`
   gains the path: edit the catalog, run the checks, open a pull request; the page is regenerated by
   a script CI re-runs, and a stale page fails the check.

## Open questions

None. (All settled 2026-10-31, see "Decided (consultation, 2026-10-31)".)

**The first wave** (Ethnologue 2026 first-language counts, whisper codes only, at or above Italian's
60 million; 24 codes, and the `zh`, `pt`, `es` and `ar` splits make the UI locales more than that):

| | | | |
|---|---|---|---|
| `zh` 988 (Mandarin; Hans + Hant) | `es` 487 (ES + 419) | `en` 372 | `hi` 347 |
| `pt` 252 (BR + PT) | `bn` 232 | `ru` 133 | `ja` 124 |
| `pa` 90 (Western Punjabi; see the caveat) | `tr` 86 | `vi` 86 | `yue` 85 |
| `ar` 83 (Egyptian + Levantine; variants) | `mr` 83 | `te` 83 | `ko` 82 |
| `ta` 79 | `ur` 78 (RTL) | `id` 78 | `de` 76 |
| `fr` 76 | `jw` 69 | `fa` 65 (RTL) | `it` 60 |

Wu Chinese (83M) and Bhojpuri (53M) sit above that line but whisper has no code for them, so they
are not part of it. Whisper's `pa` is Punjabi proper while Ethnologue's figure is Western Punjabi
(Lahnda); the item keeps `pa` in the wave and the review records whether the UI serves those
speakers.

## Translation standard (user decision 2026-11-12)

Every translated string is chosen by looking for a similar app, or a similar feature in an app,
and comparing the string with that product's own translation (German: the product's German UI).
The products are ranked by popularity: a more popular product scores higher. Each string is then
audited for gender neutrality and cultural sensitivity. The comparison is for idiomatic wording
only: a non-gender-neutral or culturally insensitive term is never accepted, even when a compared
product uses it. The locale's review record carries, per string, the product(s) and their wording,
the popularity ranking and the audit result; the review page is generated from it and the catalog
by `docs/generate-reviews.ts`. The user
approves the German translation; for a locale the user cannot review, these records and the
mechanical checks (key parity, placeholders, ICU syntax, plural categories) are the gate.

## Test list (approved 2026-10-31; behaviors only)

- [x] (setting, persisted) The settings contract carries an optional `appLanguage`: a fresh database
      has none (follow the system), a write stores a shipped locale, and a restart keeps it
- [x] (setting, page) "App language" on the Settings page lists the shipped UI locales with their own
      names, searchable by code, English name and native name, defaults to the system's language
      when it is one of them and English otherwise, and a change is written to the settings
- [x] (catalog, layer) The frontend has an English message catalog and a screen reads its strings
      from it; a component test overrides a catalog entry and sees the override rendered
- [x] (catalog, check) A check over `frontend/src` reports a user-visible literal (JSX text,
      `aria-label`, `title`, placeholder, label) with file/line, ignores the never-translated items
      (product name, file extensions, model names, whisper language codes, locale native names), and
      is green for the migrated Settings page; later migration items widen its scope
- [x] (catalog, migrate shell) The app shell — `AppHeader`, `NotFoundView`, `ConnectingRegion` —
      reads its strings from the catalog, and the check's scope includes it
- [x] (catalog, migrate queue) The queue's views read their strings from the catalog, and the
      check's scope includes them
- [x] (catalog, migrate history list) `HistoryView` and `HistoryRow` read their strings from the
      catalog, and the check's scope includes them
- [x] (catalog, migrate transcript controls) `SpeakersPanel` and `AudioPlayer` read their strings
      from the catalog, and the check's scope includes them
- [x] (catalog, migrate transcript turns) `TranscriptTurns` reads its strings from the catalog,
      and the check's scope includes it
- [x] (catalog, migrate transcript view) `TaskTranscriptView` reads its strings from the catalog,
      and the check's scope includes it
- [x] (catalog, migrate shared) The shared UI components' copy and generic messages read from the
      catalog, and the check's scope includes them
- [x] (catalog, all) The check covers all of `frontend/src` and is green
- [x] (catalog) For every shipped locale a check fails on a missing key, a changed placeholder, an
      invalid ICU message, or a missing plural category the locale requires
- [x] (errors, field) A refused settings field (a server error code) shows the catalog's message
      for that code; a code the catalog does not know shows the server's English text
- [x] (errors, surfaces) The task row, history retry/delete, speakers panel, queue pause/reorder,
      fallback dismissal and transcript load/edit/reassign/delete show the catalog's message by
      code, with the server's text as the fallback
- [x] (errors, upload) The unsupported-format refusal is a catalog message selected by its code,
      with the English as the fallback
- [x] (errors, transport) The app's read and write transport failures carry distinct codes and show
      their catalog messages, with the English as the fallback
- [x] (formats, dates) The task's finish date follows the locale's date pattern, including the
      region's month/day order, on the API's UTC date
- [x] (formats, format locale) The app derives a full format locale from the UI locale and the
      system's tags — the system's language tag when it names the same UI locale, the locale's
      canonical region otherwise — so formatting always has a region
- [x] (formats, numbers/plurals) A task's duration numbers and the catalog's plural messages follow
      the locale's rules, including Arabic's plural categories
- [x] (formats, times) The app's time labels follow the locale's time conventions
- [x] (wording) For every string, the review record names a similar app or feature and its own
      translation, the product's popularity ranking, and the gender-neutrality and
      cultural-sensitivity audit; the comparison is for idiomatic wording only, and a
      non-gender-neutral or culturally insensitive term is never accepted, even when a product
      uses it; the user approves German, and for a locale the user cannot review, these records
      and the mechanical catalog checks are the gate
- [x] (review page) Every shipped locale has a committed page generated from its record — the
      comparisons and audits it found — and the catalog: each string with its source text and
      translation, its glossary terms, the products compared with their wording and popularity,
      and the audit; `docs/generate-reviews.ts` writes the page and `docs/check-reviews.ts` fails
      when the committed page is not what it writes (or is stale against the catalog), and
      `CONTRIBUTING.md` names the path for proposing a change (edit the record, regenerate, run
      the checks, open a pull request)
- [x] (rtl) With an RTL app language the layout mirrors: navigation, rows, the speakers panel, the
      player and the settings form, with direction-aware icons; the transcript keeps its own
      direction per item 76, and a mixed-direction transcript stays readable
- [x] (wave) The first wave is the 24 whisper codes recorded in "Open questions" (every language at
      or above Italian's count), each shipped with its variants; adding the next language touches
      only its catalog, its glossary entry and its review record
- [x] (e2e, ADR 0046) The journey runs pinned to English and stays green; a second journey runs the
      upload → transcript → speakers → settings path in one wave-1 locale, and one in an RTL locale
- [x] (must not break) The English UI reads as it does today, the existing frontend, backend and
      journey suites stay green, and switching languages never reloads or loses history or settings
- [x] (discovered) (shortcuts) The editor's Save and Discard shortcuts follow the letters the
      chosen locale's catalog underlines (Alt, Ctrl or Cmd + the letter), and the buttons'
      `aria-keyshortcuts` name those letters; the English letter does nothing in a locale that
      underlines another
- [x] (discovered) (shortcuts, collisions) A check fails when a locale's editor access keys
      collide: the same letter for both actions, a missing or multi-letter underline, or a letter
      the text field or the browser already owns with Ctrl/Cmd (A, C, V, X, Y, Z for editing; N, Q,
      T, W, which the browser does not hand to the page)
- [x] (discovered) (catalog, ui-locales) The shipped-catalog check is driven by
      `docs/api/ui-locales.json`, so a language whose catalog is missing or broken is caught
      without the test file having to change
- [x] (discovered) (setting, picker) The App language picker offers only the UI locales that
      ship a catalog (and the system default follows the same set), so no one can choose a
      language that would silently stay English; the chosen language switches the whole UI
- [x] (discovered) (formats, percent) The queue phase's percent sign is appended in the component
      (`${percent}%`) rather than formatted from the locale, so Spanish and French show `42%`
      where a space belongs; the label and its number should come from one catalog/Intl
- [x] (discovered) (catalog, glob cache) A catalog added while Vitest's transform cache holds the
      old `import.meta.glob` file list stays invisible to `CATALOGS` — and so to the App language
      picker — until `node_modules/.vitest-cache` turns over, while the disk-driven
      `shippedCatalogs()` check sees it; a check asserts the picker's set equals the catalogs on
      disk, so a stale cache fails loudly instead of silently withholding a language

## Cycle log

- **Cycle 1 — 2026-11-12 — (setting, persisted).** Test:
  `backend/src/test/kotlin/stt/http/SettingsAppLanguageSpec.kt` — a fresh `GET /api/settings` has
  `appLanguage: null`, a `PUT` with `"appLanguage": "de"` answers `de`, and a restart on the same data
  directory still answers `de`. Red at `103b1760`: `java.util.NoSuchElementException: Key appLanguage
  is missing in the map.` Green: `63de0028` (`Test: store and keep the App language`), with
  `gradle test --tests 'stt.http.SettingsAppLanguageSpec'` and `gradle check`. User: approved splitting
  the setting line into (setting, persisted) and (setting, page), and this first test.

- **Cycle 2 — 2026-11-12 — (setting, page).** Test:
  `frontend/src/features/settings/SettingsView.test.tsx` — the App language field lists the shipped
  UI locales by native name (English, Deutsch, 日本語, العربية), filters by code, English name and
  native name, follows a shipped system language (de-DE → Deutsch) and falls back to English for an
  unshipped one (sv-SE), writes the chosen code as `appLanguage`, and a stored choice stands over the
  system's. Red at `59a66ae4`: `Unable to find role="combobox" and name "App language"`. Green:
  `1e53f610` (`Test: choose the App language from the shipped locales`), with the frontend test,
  prettier, `tsc` and the Vite build. User: approved the contract file, Arabic without named
  variants, and the searchable field filtered like the history search.

- **Cycle 3 — 2026-11-12 — (catalog, layer).** Test:
  `frontend/src/features/settings/SettingsView.test.tsx` — the Settings page's heading and App
  language label render from overridden catalog entries (`settings.title` → `Einstellungen`,
  `settings.appLanguage` → `App-Sprache`). Red at `f87dda5c`: `Unable to find role="combobox" and
  name "App-Sprache"`. Green: `f67adee2` (`Test: read the Settings page from the message catalog`),
  with `react-intl`, the English catalog, the provider, the frontend test, prettier, `tsc` and the
  Vite build. User: approved the catalog split and the first test.

- **Cycle 4 — 2026-11-12 — (catalog, check).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the checker reports a planted `aria-label`, `title`
  and JSX text with file/line, ignores the product name, a file extension, a whisper code and a
  locale native name, and finds the Settings page clean. Red at `5b0586c1`: the Settings page
  returned its literal list (intro, Saving…, Retry, field labels, placeholder, restore and confirm
  strings). Green: `15fd6884` (`Test: check the frontend for catalog literals`), with the checker,
  the Settings page's catalog keys, the frontend tests, prettier, `tsc` and the Vite build. User:
  approved the split into (catalog, check) and (catalog, migrate).

- **Cycle 5 — 2026-11-12 — (catalog, migrate shell).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds `AppHeader`, `NotFoundView`
  and `ConnectingRegion`. Red at `6b49c391`: `Speech-to-Text home`, Queue, History, Settings,
  `Not found`, `This page doesn't exist`, the detail and `Go to the queue`. Green: `8f76b1c0`
  (`Test: read the app shell from the message catalog`), with the shell keys in `en.json`, the
  header, not-found and connecting labels from the catalog, the frontend tests, prettier, `tsc` and
  the Vite build. User: approved the area split.

- **Cycle 6 — 2026-11-12 — (catalog, migrate queue).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds `QueueView`, `TaskRow`,
  `SortableTaskRow`, `UploadOptionsPanel` and `DeviceFallbackBanner`. Red at `75ac864e`: the queue's
  literals (Already uploaded, open, see History, Queue, Uploads, Pause the queue, Pending tasks,
  Transcription, the cancel and delete labels and bodies, the upload options, the fallback notice
  and Dismiss). Green: `a6ad4363` (`Test: read the queue from the message catalog`), with the queue
  keys, the row, upload, fallback, phase, menu and confirm copy from the catalog, the queue tests
  wrapped in the locale provider, prettier, `tsc` and the Vite build.

- **Approved refactor after cycle 6 — 2026-11-12:** `Refactor: use a message helper in the task
  row` (`21c5315c`) — the queue row's single-argument messages go through one `t` helper, the
  `{ filename }`/`{ verb }` calls stay explicit; the queue tests, prettier and `tsc` are green.

- **Cycle 7 — 2026-11-12 — (catalog, migrate history list).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds `HistoryView` and
  `HistoryRow`. Red at `21c5315c`: History, Search, Search by filename, Status, Filter by status,
  the no-matches line, Clear filters, No transcripts yet, the empty-state line and link, Retry,
  Delete, and the delete dialog title and label. Green: `9517d6f7` (`Test: read the history list
  from the message catalog`), with the history keys, the status filter and empty state from the
  catalog, the history tests, prettier, `tsc` and the Vite build.

- **Approved refactor after cycle 7 — 2026-11-12:** `Refactor: render the history empty state as
  one message` (`3961750f`) — one ICU message with a link chunk, so a translator can reorder the
  sentence around the link; the history tests, prettier and `tsc` are green.

- **Cycle 8 — 2026-11-12 — (catalog, migrate transcript controls).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds `SpeakersPanel` and
  `AudioPlayer`. Red at `3961750f`: Speakers, Reassign speakers, Merge speakers…, Reset all merges,
  Keep, Merge, Close, Merge speakers?, Reset name, Audio player, Seek. Green: `63cb4a9a` (`Test:
  read the transcript controls from the message catalog`), with the player and speakers keys, the
  merge body and speaker labels as ICU messages, the transcript tests wrapped in the locale
  provider, prettier, `tsc` and the Vite build.

- **Cycle 9 — 2026-11-12 — (catalog, migrate transcript turns).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds `TranscriptTurns`, and the
  checker now strips comments and ignores single-character text. Red at `947f2d79`: Segment text,
  Revert, Edit segment. Green: `d737f60a` (`Test: read the transcript turns from the message
  catalog`), with the selection, editor and segment-control keys, the Save and Discard shortcuts
  as rich-text messages, the editor's focus returning through a stable data attribute, the
  transcript tests, prettier, `tsc` and the Vite build.

- **Cycle 10 — 2026-11-12 — (catalog, migrate transcript view).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds `TaskTranscriptView`. Red
  at `923e8869`: Retry, Task not found, the not-found callout, Back to History, the delete dialog,
  the pending page, the no-audio note, the load error, Loading transcript…, the reassign bar and
  confirmation, Transcript, and Back to playing position. Green: `4cba2cf4` (`Test: read the
  transcript view from the message catalog`), with the transcript view keys, the outcome lines and
  selection count as ICU messages, the `t` helper forwarding its values, the transcript tests,
  prettier, `tsc` and the Vite build.

- **Approved refactor after cycle 10 — 2026-11-12:** `Refactor: share one message helper across the
  views` (`837e7c9a`) — `useMessages` replaces the repeated `useIntl` + `t` pairs across the app
  shell, queue, history and settings views; the frontend tests, prettier and `tsc` are green.

- **Cycle 11 — 2026-11-12 — (catalog, migrate shared).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope adds every shared UI component,
  and the checker ignores code between tags that starts with a `return`/`const` statement. Red at
  `837e7c9a`: Cancel, Speaker diarization. Green: `5dfed5f6` (`Test: read the shared UI from the
  message catalog`), with the shared cancel, status, device and diarization keys, `StatusBadge`
  and `deviceLabel` reading them, the shared test render including the locale provider, the
  frontend tests, prettier, `tsc` and the Vite build.

- **Cycle 12 — 2026-11-12 — (catalog, all).** Test:
  `frontend/src/shared/i18n/catalog.test.ts` — the check's scope is now every non-test source
  under `/src` (the conventions check's filter). Red: none for this final ratchet — the behavior
  was reached by the incremental scopes of cycles 4–11; the whole-tree scope is green at
  `45df5f57`, and the test's planted-snippet assertions still prove the failure path. Green:
  `374694d3` (`Test: check the frontend for catalog literals everywhere`), with the whole-tree
  scope, the frontend tests, prettier, `tsc` and the Vite build.

- **Cycle 13 — 2026-11-12 — (catalog).** Test:
  `frontend/src/shared/i18n/catalogParity.test.ts` — a parity checker over the shipped catalogs
  catches a missing key, a stale key, a changed placeholder, invalid ICU and a plural message
  missing a category the locale requires; the en catalog and a clean German fixture pass. Red at
  `381d232c`: the stub returned no violations (expected 5). Green: `c3880b6f` (`Test: check every
  catalog against the English one`), with `@formatjs/icu-messageformat-parser` as a direct dev
  dependency, the parity checker, the test, prettier, `tsc` and the Vite build.

- **Cycle 14 — 2026-11-12 — (errors, first slice — Settings field).** Test:
  `frontend/src/features/settings/SettingsView.test.tsx` — a rejected threads write with
  `invalid-threads` shows the catalog's message for the code, not the server's; a code the
  catalog does not know shows the server's English text. Red at `c9bd733d`: both known refusals
  still showed the server message. Green: `3895fcfe` (`Test: translate a refusal by its code in
  settings`), with `useErrorText`, the `error.invalid-threads` entry and the Settings field/callout
  wiring; the frontend tests, prettier, `tsc` and the Vite build. User: approved the errors item;
  the remaining refusal surfaces are proposed as a split.

- **Cycle 15 — 2026-11-12 — (errors, surfaces).** Test:
  `frontend/src/features/queue/QueueView.test.tsx` — a task-row action rejected with `not-found`
  shows the catalog's text, and an unknown code shows the server's; the other refusal surfaces
  were moved onto the same helper. Red at `e6e1263a`: the row still showed the server text. Green:
  `b4190559` (`Test: translate refusals by their codes across the screens`), with the field, row,
  history, speakers, queue, transcript and fallback wiring and their updated tests, prettier, `tsc`
  and the Vite build. User: approved the split and the surfaces slice.

- **Approved refactor after cycle 15 — 2026-11-12:** `Refactor: share the refusal failure line`
  (`54111c52`) — `failureLine` replaces the duplicated transport-aware prefix logic in the
  history row and the transcript; the history and transcript tests, prettier and `tsc` are green.

- **Cycle 16 — 2026-11-12 — (errors, transport/upload, upload slice).** Test:
  `frontend/src/features/queue/QueueView.test.tsx` — an unsupported `.txt` shows the catalog's
  `error.unsupported-format` (overridden in the test), not the machine's literal. Red at
  `54111c52`: the row still showed the literal. Green: `99d7a014` (`Test: read the upload format
  refusal from the catalog`), with the upload row's error as an `ApiError` code, the row rendering
  `errorText`, the catalog entry, and the test-override merge in `LocaleProvider`; the frontend
  tests, prettier, `tsc` and the Vite build. The transport messages remain.

- **Cycle 17 — 2026-11-12 — (errors, transport).** Test:
  `frontend/src/shared/i18n/transportErrors.test.tsx` — a network-failed read and write carry
  `transport-read` and `transport-write`, and the catalog (overridden in the test) shows each
  message. Red at `97016085`: both were `0`. Green: `a9adae18` (`Test: read transport failures
  from the catalog`), with the split codes, the client choosing by method, the catalog entries,
  the frontend tests, prettier, `tsc` and the Vite build.

- **Cycle 18 — 2026-11-12 — (formats, first slice — finish date).** Test:
  `frontend/src/features/history/formatTask.test.ts` — the task's finish date follows the locale's
  pattern (en-US `Oct 9, 2026`, en-GB `9 Oct 2026`, de-DE `9. Okt. 2026`) on the API's UTC date.
  Red at `9b0cc282`: en-GB still showed `Oct 9, 2026` (the fixed formatter ignored the locale).
  Green: `e0b23a1d` (`Test: format the finish date in the locale's pattern`), with
  `formatTaskMeta` taking the `IntlShape` and formatting through `intl.formatDate` with UTC, and
  the history row and the transcript passing `useIntl`'s formatter; 47 test files / 322 tests,
  prettier, `tsc` and the Vite build, run through the `.bin` shims with `sh` because this sandbox
  has no `/bin/sh` for `pnpm`'s script shell. The formats item stays unchecked: this is its date
  slice; its numbers/plurals and time slices are proposed at the checkpoint.

- **Cycle 19 — 2026-11-12 — (formats, format locale).** Test:
  `frontend/src/shared/i18n/uiLocales.test.ts` — a UI locale plus the system tags gives a full
  format tag: `en` + `en-GB` → `en-GB`, `de` + `en-US` → `de-DE` (canonical), `pt-BR` + `pt-PT` →
  `pt-BR` (the explicit variant), `en` with no matching tag → `en-US`. Red at `ec628b42`:
  `formatLocaleFor is not a function`. Green: `6bd4f680` (`Test: resolve the format locale with a
  region`), with `formatLocale` on every `ui-locales.json` entry, `formatLocaleFor` reusing
  the system-tag match, the docs/api README note, the formats test list split, the frontend tests,
  prettier, `tsc` and the Vite build.

- **Approved refactor after cycle 19 — 2026-11-12:** `Refactor: share the UI locale tag match`
  (`7aee4ee7`) — `matchTag` replaces the duplicated normalize/exact/baseMatch logic in
  `resolveUiLocale` and `formatLocaleFor`; the locale tests, the frontend tests, prettier, `tsc` and
  the Vite build are green.

- **Cycle 20 — 2026-11-12 — (formats, numbers/plurals, plural slice).** Test:
  `frontend/src/shared/i18n/pluralCategories.test.tsx` — through `LocaleProvider` with `ar-EG`, a
  catalog plural message selects `zero one two few many other` for counts 0, 1, 2, 3, 11, 100 (the
  dual at 2). Red at `22f89d58`: the provider used the English rules and rendered `other one other
  other other other`. Green: `7e18adbf` (`Test: select the plural categories by the format locale`),
  with `LocaleProvider`'s `locale` prop passed to `IntlProvider` (default `en`); the
  frontend tests, prettier, `tsc` and the Vite build. The numbers slice of the line remains.

- **Cycle 21 — 2026-11-12 — (formats, numbers/plurals, numbers slice).** Test:
  `frontend/src/shared/formatDuration.test.ts` — the duration's digits follow the format locale:
  `ar-EG` writes `٠٢:٠٥` for 125000 ms and `١:٠٢:٠٥` for 3725000 ms, `en-US` keeps `02:05` and
  `1:02:05`; the colons and the shape stay. Red at `24543811`: `expected '02:05' to be '٠٢:٠٥'`
  (the formatter was ASCII-only). Green: `a67c2b73` (`Test: format the duration digits by the
  format locale`), with `formatDuration` taking the `IntlShape` and the queue row, the audio
  player, the transcript turns and the task lines passing it; the frontend tests, prettier, `tsc`
  and the Vite build. The numbers/plurals line is done; (formats, times) remains.

- **Formats item complete — 2026-11-12 (checkpoint).** The user approved the times slice as
  covered: every user-visible time string is a duration (the player's current/total, the queue's
  elapsed, the transcript's turn and segment timestamps, the outcome time), formatted by
  `formatDuration` in the format locale; the finish date is covered by the dates slice. All four
  formats lines are ticked.

- **Cycle 22 — 2026-11-12 — (wave, first slice — German catalog).** Test:
  `frontend/src/shared/i18n/catalogParity.test.ts` — the shipped German catalog matches the
  English reference (keys, placeholders, ICU, plural categories). Red at `e3975580`: the empty
  catalog gave 199 `missing-key` violations. Green: `86923718` (`Test: ship the German catalog
  with its review record`), with `frontend/src/locales/de.json` (199 keys, neutral
  speaker terms and `am …` queue phases), the German parity test, `docs/reviews/de.md`,
  `docs/glossary/de.md` and `docs/style-guides/de.md`; the frontend tests (50 files, 326 tests),
  prettier, `tsc` and the Vite build. The user approved the German ("Much better") and fixed the
  policy: the product comparison is for idiomatic wording only; a non-gender-neutral or
  culturally insensitive term is never accepted, even when a product uses it. The wave has 23
  locales left; the wording/review-page items remain for all locales.

- **Cycle 23 — 2026-11-12 — (wave, second slice — French catalog).** Test:
  `frontend/src/shared/i18n/catalogParity.test.ts` — the shipped French catalog matches the
  English reference. Red at `155d1310`: the empty catalog gave 199 `missing-key` violations (the
  shipped-catalog loop only saw the new file once the test file changed, so the French test was
  added beside the German one); the check then caught French's `many` plural category. Green:
  `e26d861b` (`Test: ship the French catalog with its review record`), with
  `frontend/src/locales/fr.json` (199 keys, `vous`, epicene `voix`, French typographic spaces),
  the French parity test, `docs/reviews/fr.md`, `docs/glossary/fr.md` and
  `docs/style-guides/fr.md`; the frontend tests (50 files, 327 tests), prettier, `tsc` and the
  Vite build. The user reviewed and confirmed the French. The wave has 22 locales left.

- **Approved refactor after cycle 23 — 2026-11-12:** `Refactor: drive the shipped catalog tests
  from one table` (`fe5d67c1`) — the German and French catalog tests became one `it.each` row
  each; the parity tests, prettier and `tsc` are green.

- **Cycle 24 — 2026-11-12 — (discovered, shortcuts).** Test:
  `frontend/src/features/history/TaskTranscriptView.test.tsx` — with a catalog that underlines
  `E` and `I`, the buttons advertise `Alt+E Control+E Meta+E` / `Alt+I Control+I Meta+I`, Alt+D
  leaves the editor open, Alt+I discards and Ctrl+E saves the changed draft. Red at `fe5d67c1`:
  the Save button still advertised `Alt+S Control+S Meta+S`. Green: `d944d989` (`Test: follow
  the translated access keys in the segment editor`), with the editor reading each
  button's underlined letter from the catalog for its handler and `aria-keyshortcuts`; the
  English shortcut test stays green, the frontend tests, prettier, `tsc` and the Vite build.

- **Cycle 25 — 2026-11-12 — (discovered, shortcuts, collisions).** Test:
  `frontend/src/shared/i18n/accessKeys.test.ts` — planted catalogs give a `collision` (S twice),
  two `not-one-letter` (no underline, `<u>Di</u>`) and two `reserved` (W, V); the shipped en, de
  and fr catalogs pass. Red at `7cd68323`: the stub check reported nothing. Implementing it
  reported the German `<u>V</u>erwerfen` (Ctrl+V pastes in the field); with the user's approval
  the German Discard became `V<u>e</u>rwerfen`. Green: `221ff036` (`Test: reject colliding or
  reserved access keys`), with `checkAccessKeys` and its reserved letters, the
  German catalog, review page, glossary and style guide, the French style guide note, the
  frontend tests, prettier, `tsc` and the Vite build.

- **Approved refactor after cycle 25 — 2026-11-12:** `Refactor: share one access-key reader
  between the editor and the check` (`059660a0`) — `accessKeyOf` lives in `accessKeys.ts`, so the
  letter the editor listens for and the letter the check validates are the same reading; the
  i18n and transcript tests and `tsc` are green.

- **Cycle 26 — 2026-11-12 — (discovered, catalog, ui-locales).** Test:
  `frontend/src/shared/i18n/catalogParity.test.ts` — `checkShippedCatalogs` reports a catalog
  named after a locale `docs/api/ui-locales.json` does not list (`unknown-locale`), checks every
  other against English, and passes the catalogs read from disk; `accessKeys.test.ts` reads them
  the same way. Red at `059660a0`: the stub reported no stray catalog. Cause of the blind spot:
  Vitest's transform cache (`node_modules/.vitest-cache`) bakes the `import.meta.glob` file list
  into the cached test module; `src/test/catalogs.ts` now reads `src/locales` from disk on each
  run (`@types/node` 26.6.6 added as a frontend dev dependency for it). Verified: a stray
  `xx.json` dropped into `src/locales` failed the next run with the test file unchanged, and
  passed again once removed. Green: `94403183` (`Test: check every shipped catalog from disk
  against ui-locales.json`), with the frontend tests (51 files, 329 tests — the per-locale rows
  folded into the disk-driven tests), prettier, `tsc` and the Vite build.

- **Cycle 27 — 2026-11-12 — (discovered, setting, picker).** Tests:
  `frontend/src/features/settings/SettingsView.test.tsx` (the picker test, its expectations
  changed by this scenario) — only Deutsch, English and Français are offered, Japanese and
  Arabic are not; search by `french`, `Français`, `fr`; choosing French stores `fr`; a system of
  `ja-JP, fr-FR` defaults to French; a stored `de` stands over the system; and
  `frontend/src/app/App.test.tsx` — a `hi-IN, de-DE` system reads the app in German, and
  choosing Français in the picker switches the headings, navigation and the field's own label
  to French without a reload. Red at `78fba609`: the picker still offered Japanese and the app
  stayed English. Green: `6a31c56e` (`Test: offer only shipped languages and switch the UI to
  the chosen one`), with `CATALOGS` (every `src/locales/*.json`),
  `SHIPPED_UI_LOCALES`, `resolveUiLocale` passing over locales without a catalog,
  `effectiveUiLocale`, and `SettingsLocaleProvider` inside the settings provider feeding
  `LocaleProvider` the chosen catalog and format locale; the frontend tests, prettier, `tsc`
  and the Vite build. The user asked for the catalog wiring to be part of this cycle. Four
  shared-settings tests in `App.test.tsx` loaded `docs/api/settings.fixed.json`, whose stored
  `appLanguage: "de"` the app now follows, so they ran in German; they now load the same
  example without a stored App language (the contract file is unchanged: backend specs read
  it). The bundle grew from 681 kB to 703 kB with the two catalogs loaded eagerly; lazy
  loading per language is noted for the wave.

- **Approved refactor after cycle 27 — 2026-11-12:** `Refactor: read the system languages in one
  place` (`b32053be`) — `systemLanguageTags()` replaces the two `navigator.languages` +
  `navigator.language` expressions in the locale provider and the Settings page; the app shell,
  settings and i18n tests and `tsc` are green.

- **Cycle 28 — 2026-11-12 — (wave, third slice — Spanish).** Test: the disk-driven checks, no
  test edited: `catalogParity.test.ts` and `accessKeys.test.ts` read `src/locales/es-ES.json`
  from disk once it exists. Red at `b32053be` with the empty catalog: 199 `missing-key` and two
  `not-one-letter` violations. Green: `0033a3b2` (`Test: ship the Spanish catalog with its
  review record`), with `frontend/src/locales/es-ES.json` (199 keys; `tú`; epicene `voz`/
  `voces` with the products' `hablante` recorded as rejected; bare-noun phases; `¿…?`, `«»`, a
  space before `%`; `one`/`many`/`other` plurals; access keys G and D), `docs/reviews/es-ES.md`,
  `docs/glossary/es-ES.md` and `docs/style-guides/es-ES.md`; the frontend tests, prettier, `tsc`
  and the Vite build. The user approved the Spanish wording (they cannot judge it closely and
  rely on the gates; the reset label `Deshacer el cambio de nombre de la voz` stays).

- **Cycle 29 — 2026-11-12 — (discovered, formats, percent).** Test:
  `frontend/src/features/queue/QueueView.test.tsx` — with the Spanish catalog and the `es-ES`
  format locale, the running row reads `Transcripción… 42 %` (the percent from
  `Intl.NumberFormat('es-ES', { style: 'percent' })`), and the English row keeps
  `Transcribing… 42%`. Red at `bde5325b`: Spanish still showed `42%` because the sign was
  appended in the component. Green: `6a13acbd` (`Test: format the queue phase percent for the
  locale`), with `TaskRow` formatting the percent through its `IntlShape`; the queue
  tests, the frontend tests, prettier, `tsc` and the Vite build.

- **Cycle 30 — 2026-11-12 — (wave, fourth slice — Latin American Spanish).** Test: the
  disk-driven checks, no test edited: `catalogParity.test.ts` and `accessKeys.test.ts` read
  `src/locales/es-419.json` from disk once it exists. Red at `9bbfe7a4` with the empty catalog:
  199 `missing-key` and two `not-one-letter` violations. Green: `eb439357` (`Test: ship the
  Latin American Spanish catalog with its review record`), with
  `frontend/src/locales/es-419.json` (the Spanish text with the regional choices: `subir`/
  `subida` following Google Drive, `cargar` recorded; `tú`, no plural `ustedes` needed; the
  `es-419` format locale; the same epicene `voz`/`voces` and typography), `docs/reviews/
  es-419.md`, `docs/glossary/es-419.md` and `docs/style-guides/es-419.md`; the Spanish (Spain)
  style guide's percent note corrected to the fixed `Intl` call; the frontend tests, prettier,
  `tsc` and the Vite build.

- **Cycle 31 — 2026-11-12 — (wave, fifth slice — Arabic).** Test: the disk-driven checks, no
  test edited: `catalogParity.test.ts` and `accessKeys.test.ts` read `src/locales/ar.json` from
  disk once it exists. Red at `6dc0788d` with the empty catalog: 199 `missing-key` and two
  `not-one-letter` violations. Green: `32f0ef91` (`Test: ship the Arabic catalog with its review
  record`), with `frontend/src/locales/ar.json` (199 keys; Modern Standard Arabic;
  nominal/masdar address; epicene `الأصوات`/`المداخلة`, the products' `المتحدثون` recorded as
  rejected; the six Arabic plural categories; `؟`/`،`/`؛`/`٪`/`«»`; access keys ح and ت; the
  `ar-EG` format locale), `docs/reviews/ar.md`, `docs/glossary/ar.md` and
  `docs/style-guides/ar.md`; the frontend tests, prettier, `tsc` and the Vite build. The user
  approved the Arabic wording; the RTL item is next.

- **Cycle 32 — 2026-11-12 — (RTL, first slice — the document direction).** Test: the app's
  document direction and language follow the UI locale (`App.test.tsx`: the settings return `ar`;
  the heading is the Arabic catalog's; `document.documentElement.dir` `rtl` and `lang` `ar`, then
  the English default `ltr`/`en` after a second render). Red at `4520f863` (the worktree; the
  `catalogs.ts` comment and the test): the app never sets `dir`/`lang` (no `dir` on the document;
  `lang` stays the `index.html`'s `en`). The `catalogs.ts` comment (Vitest's transform cache bakes
  the glob; the test app must see the ar) is part of the green, as is the `SettingsView` picker
  test's ar assertion (the ar now ships). Green: `uiDirection` in `uiLocales.ts` (the `UI_LOCALES`
  direction, `ltr` when unknown) and `SettingsLocaleProvider`'s `useEffect` writing
  `document.documentElement.lang`/`dir`. The full gates: 51 files / 332 tests, prettier, `tsc` and
  Vite build (773 kB; five locales). Item 9 (RTL) stays open: the layout mirroring (logical
  properties), the direction-aware icons and the transcript's own direction are the next slices.

- **Cycle 33 — 2026-11-12 — (RTL, second slice — the shell's logical layout).** Test: the shell's
  sources use no physical side/text-alignment/float/inset CSS (the centred dialogs' `left: 50%`
  stays), the shell's lucide icons are direction-neutral, and the transcript keeps its own
  `dir="auto"` (`rtl.test.ts`, a source scan in the design-value convention's pattern). Red at
  `a2a2195f` (the worktree with the test): five violations — the header indicator's `margin-left`,
  the error callout's `border-left`, the progress fill's `left: 0` and `left: 30%` and the shared
  fill's `left: 0`. Green: `margin-inline-start`, `border-inline-start` and
  `inset-inline-start`; the affected comments say inline start/end. The callout's two jsdom
  border assertions (the computed physical colour/width) moved to the styled source, because
  jsdom's CSS engine drops `border-inline-start`; the browser journey carries the rendered side.
  The full gates: 52 files / 333 tests, prettier, `tsc` and the Vite build. Item 9 stays open: the
  Radix components (the seek slider, the per-task menus) still need the direction, and the
  transcript's mixed-direction journey is next.

- **Cycle 34 — 2026-11-12 — (RTL, third slice — the Radix controls' direction).** Test: with an
  Arabic app language the player's seek bar takes the app's direction, so the arrow keys run the
  other way — ArrowRight decreases from 0 (stays), ArrowLeft increases to 1
  (`TaskTranscriptView.test.tsx`: the Arabic settings and the slider's `aria-valuenow`). Red at
  `48670bb8` (the test added): the Radix slider stayed LTR, so ArrowRight read 1. Green:
  `UiDirectionContext`/`useUiDirection` (`shared/i18n/uiDirection.ts`), provided by
  `SettingsLocaleProvider` from `uiDirection(uiLocale)`; the shared `Slider` and `DropdownMenu`
  pass the direction to Radix's root (`dir`), so the seek bar and the per-task menus follow it.
  The full gates: 52 files / 334 tests, prettier, `tsc` and the Vite build. Item 9 stays open: the
  menus' rendered alignment and the mixed-direction transcript journey are the e2e's.

- **Cycle 35 — 2026-11-13 — (e2e — the German journey).** Test: `e2e/tests/88-localization.spec.ts`,
  the German journey — the App language to Deutsch, the shell in German (heading, navigation,
  header, field), then upload → transcript → speakers panel → settings with the stored task data
  (the `Speaker 1` name, the English transcript) unchanged. Red was the user's runs: the nav's
  `Warteschlange` matched the back link too (exact), then stale locators (the English-named field
  after the switch, the interface-language expectation for the stored speaker name). Green after
  the fixes, the user's run: 3.7 s. `goTo` takes the localized nav name; `tsc` and prettier green.
- **Cycle 36 — 2026-11-13 — (e2e — the Arabic RTL journey).** Test: the same file, the Arabic
  journey — the app language to العربية, `html[dir="rtl"]`, the Arabic shell, the same path on
  the shared backend. Red was the user's run: the German journey stores its language first, so the
  test's English-start assumption hung (fixed: the navigation's last link + the `#appLanguage`
  id), and the `dir="auto"` assertion targeted the segment editor's textarea rather than the turn
  (dropped; item 76's direction is the transcript's own). The user's run: 995 ms, both journeys
  green. The e2e test-list item is ticked: the pinned-English suite stays the English journey.

- **Cycle 37 — 2026-11-13 — (wave, sixth slice — Hebrew).** Test: the shipped-catalog checks
  (`catalogParity.test.ts`, `catalog.test.ts`) read `src/locales/he.json` from disk once it exists.
  Red after the first draft: `he` was missing the `two`/`many` plural categories
  (`missing-plural-category`); green with `{count, plural, one … two … many … other …}`. Added the
  `ui-locales.json` entry (he, עברית, `rtl`, `he-IL`) and `he.json` (199 keys; action nouns and the
  plural address instead of gendered imperatives, RTL punctuation, access keys ש/ב), with
  `docs/glossary/he.md`, `docs/style-guides/he.md` and `docs/reviews/he.md` (Hebrew is the
  user's first language; they reviewed and approved the translations). The full gates: 52 files
  / 334 tests, prettier, `tsc`, the Vite build, and the docs package's checks. The wave has 6 of
  the 24; 18 remain.

- **Cycle 38 — 2026-11-13 — (wave, seventh slice — Portuguese (Brazil)).** Test: the shipped-catalog
  checks (`catalogParity.test.ts`, `catalog.test.ts`) and the access-key check read
  `src/locales/pt-BR.json` from disk. Red with the empty catalog: 199 `missing-key` violations;
  after the first full draft, parity's `missing-plural-category` caught Portuguese's `many` (the
  1000000 CLDR category), added to `transcript.reassign.selected`; green. `pt-BR.json` (199 keys;
  `você` address, common-gender `falante`/`falantes`, `threads` kept, access keys S/D), with
  `docs/glossary/pt-BR.md`, `docs/style-guides/pt-BR.md` and `docs/reviews/pt-BR.md`. The full
  gates: 52 files / 334 tests, prettier, `tsc`, the Vite build, and the docs package's formatting.
  The wave has 7 of the 24; 17 remain.

- **Cycle 39 — 2026-11-13 — (wording — Hebrew plurals and the independent reviews).** The two
  independent reviewers' findings went into `he.json` and `pt-BR.json` (Hebrew: `Speech-to-Text`
  kept, `השארה`/`המשך`, `CPU`/`GPU` kept, the control-label colon, `קטע אחד נבחר`, gershayim,
  inclusive `מ־1 עד 20`, `הפרדת קולות` for diarization; Portuguese: noun phrases after `durante`,
  the `=0` plural branch, `desfazer`/`salvar`, sentence case, the badge agreement, `Embedding`,
  `desses falantes`), and the review tables were rebuilt from per-key glossary terms. Commits
  `145bc51e` and `c59aec86`; gates green (52 files / 334 tests, prettier, `tsc`, Vite build).
- **Cycle 40 — 2026-11-13 — (wording, user decision — the user interface).** `frontend` left the
  English, German, Brazilian and European Portuguese catalogs; the term is the user interface
  (`Default user interface`, `Standard-Benutzeroberfläche`, `Interface do usuário/utilizador`,
  matching the Spanish, French and Hebrew `interfaz`/`interface`/`ממשק`). The Settings tests, the
  write-gate test and the two e2e selectors follow the English label; 52 files / 334 tests green.
- **Cycle 41 — 2026-11-13 — (wave, eighth slice — Portuguese (Portugal)).** Test: the
  disk-driven shipped-catalog check (`catalogParity.test.ts`) over `src/locales/pt-PT.json`, and a
  throwaway assertion that `SHIPPED_UI_LOCALES` offers pt-PT with its native name and 199 keys.
  The catalog starts from the reviewed pt-BR and changes the European layer only (ficheiro,
  definições, eliminar, guardar, predefinido, ecrã, ligação, transferir, utilizador, aceder,
  aplicação; `a` + infinitive progress, no `você`); `docs/glossary/pt-PT.md`,
  `docs/style-guides/pt-PT.md` and `docs/reviews/pt-PT.md` record the 39 differing lines. Full
  gates from a cleared Vitest cache: 52 files / 334 tests, prettier, `tsc`, the Vite build, and
  the docs package's `tsc`, formatting and 44 checks. The wave has 8 of the 24; 16 remain.

- **Cycle 42 — 2026-11-13 — (discovered, catalog, glob cache).** Test:
  `frontend/src/shared/i18n/uiLocales.test.ts` — `CATALOGS`'s keys and `SHIPPED_UI_LOCALES`'
  codes equal the catalogs in `src/locales` read from disk. Red observed live before the test:
  with a warm `node_modules/.vitest-cache` the throwaway assertion
  `expect(codes).toContain('pt-PT')` failed (`expected ['es-ES','es-419','en','ar',…] to include
  'pt-PT'`) while the disk-driven parity check had seen all nine catalogs; after clearing the
  cache the same assertion passed. Green: the new test plus the full suite (52 files / 335
  tests), prettier, `tsc`, the Vite build. The e2e selector regression from cycle 40 (the reload
  assertion still asked for "Default frontend") is fixed in `tests/66-settings.spec.ts`; the user
  ran `tests/66-settings.spec.ts`, `tests/87-writes-wait-for-the-connection.spec.ts` and
  `tests/88-localization.spec.ts` with `E2E_BACKEND_CMD=backend/build/install/stt-backend/bin/stt-backend`
  (dev mode, as `gradlew e2e` sets it): 6 passed, the German and Arabic journeys included. A
  bare `pnpm exec playwright test` starts the app-mode service instead, whose in-process sessions
  die at a restart, so the reconnect specs cannot pass there.

- **Cycle 43 — 2026-11-13 — (wave, ninth slice — Italian).** The user chose Italian ("Do
  Italian"), the last language of the wave's boundary. Test: the disk-driven shipped-catalog
  checks (`catalogParity.test.ts`, `accessKeys.test.ts`) and the picker check
  (`uiLocales.test.ts`) over `src/locales/it.json`. The first run of the picker check went red
  exactly as cycle 42 predicts (`expected ['ar','de','en','es-419',…] to deeply equal [… 6]`)
  until the Vitest cache was cleared; then 6/6 green. `it.json` (199 keys): `tu` address,
  `voce`/`voci` for speakers (never `parlante`/`locutore`), `identificazione delle voci`,
  statuses agreeing with `attività`, access keys S/I, plurals `one`/`many`/`other` (`# di …`),
  `interfaccia utente` rather than `frontend`; `docs/glossary/it.md`,
  `docs/style-guides/it.md` and `docs/reviews/it.md` (199 rows). The wave has 9 of its 27 UI
  locales (the 24 whisper codes with their splits); 18 remain.

## Acceptance criteria

- Every screen and message of the app reads from the catalog in the first wave's locales; switching
  the App language changes the whole interface without a reload of the app.
- Each shipped locale has its glossary entry, style guide and a review record naming the products
  compared and the wording they use, plus a committed review page a reader can open without running
  the app; a translation change goes through a pull request like any other change.
- The mechanical checks (key parity, placeholders, ICU syntax, plural categories, no hard-coded
  strings, review pages current) pass in CI, and the screenshot set per locale is reviewed in its CI
  artifact.
- The journey is green in its pinned locale and in one RTL locale.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-11-13: the user approved giving the transcript's turn text `dir="auto"` (review fix 2): an
  RTL app language must not mirror an English transcript, and item 76 refines the per-item
  direction later. The Arabic journey asserts the turn's `dir` and its computed `ltr` direction.
- 2026-11-13: the user settled the (wording) reading: best effort to find a comparison for every
  string, and where no comparable product feature or wording exists the row stays `—` — the
  record's own definition, "no comparison was found" — never an invented one. The check fails a
  product name without its wording. With that definition the line is re-ticked: the per-locale
  fills (pt-PT, pt-BR, he, zh-Hans, zh-Hant, de, ru) are recorded in cycles 67-74 and every page
  states what its product pages do and do not cover; the rows still `—` are the app's own
  vocabulary (queue, task, speaker, merge, segment, retry, phases, errors), which the compared
  products do not expose.

- 2026-11-13: the root review found the (wording) line ticked while 2,457 rows carried `—` and 675
  named a product without its wording; the user chose to fill the comparisons, starting with pt-PT.
  The line is unticked again while that fill runs — a row that names a product without its wording
  now fails `docs/check-reviews.ts` — and is re-ticked when every row carries a comparison or an
  honest `—` with its reason. pt-PT is the first slice: Apple's Portuguese (Portugal) wording
  („Definições“, „dispositivo“, „descarregar“, „ficheiros“, „apagar“) is recorded where it applies;
  Google serves Brazilian Portuguese at `hl=pt-PT`, so it is not cited for pt-PT.

- 2026-11-13: the user confirmed the four lines whose behaviors are now green — (wording), (rtl),
  (wave) and (must not break) — and asked for the next test to be the (review page) line.

- 2026-10-31: filed as a draft by the root agent at the user's request: translate the app into every
  language whisper transcription supports, with terminology compared against established products,
  gender-neutral wording and cultural sensitivity. Not yet discussed.
- 2026-10-31: consultation with the user: a first wave by the number of first-language speakers,
  then the rest; UI variants for zh, pt, es and ar with a mapping to the transcription code; agent
  draft + independent agent review against a rubric + native-speaker spot checks; formatjs/react-intl;
  errors translated by code with the server's English as fallback (server localization may come
  later); RTL in the same item; stored in the database, default from the system; after 1.0. The
  first wave's boundary set at Italian: 24 whisper codes (Ethnologue 2026 counts, Wu Chinese and
  Bhojpuri absent from whisper, `pa` kept with its caveat recorded). Test list proposed.
- 2026-10-31: the user: screenshots stay a CI artifact; every locale gets a committed, browsable
  review page and a documented pull-request path so anyone can propose a translation change; a
  native-speaker check is best effort only, and the evidence for a wording is the comparison with
  established products, recorded per term. Test list approved as amended; status set to spec-approved.
- 2026-11-12: the user approved splitting the (setting) test-list line into (setting, persisted) — the
  settings contract's `appLanguage`, null on a fresh database and kept across a restart — and
  (setting, page) — the Settings-page field, its native names and its system fallback — so each gets
  exactly one test; and approved the first test, `SettingsAppLanguageSpec`.
- 2026-11-12: the user approved `docs/api/ui-locales.json` as the shipped-locale contract data; Arabic
  needs no named variants — the transcription code `ar` covers dialectal speech, so the UI ships one
  Arabic locale; and the App language field is a searchable select, filtered like the history search
  by the locale's code, English name and native name.
- 2026-11-12: the user approved splitting (catalog) into (catalog, layer) — the English catalog and
  one screen reading it, with `react-intl` — and (catalog, no literals) — the check that fails on a
  user-visible literal outside the catalog — and approved the first test.
- 2026-11-12: the user approved splitting (catalog, no literals) into (catalog, check) — the checker
  with file/line, the never-translated allow-list and the Settings page as its first scope — and
  (catalog, migrate) — each remaining area moves its strings to the catalog and the scope grows.
- 2026-11-12: the user approved splitting (catalog, migrate) by area — shell, queue,
  history/transcript, shared UI, then all of `frontend/src` — each widening the check's scope.
- 2026-11-12: the user approved splitting (errors) into (errors, field), (errors, surfaces) and
  (errors, transport/upload), and approved the field and surfaces slices.
- 2026-11-12: the user approved splitting (errors, transport/upload) into (errors, upload) and
  (errors, transport), and approved both slices.
- 2026-11-12: formatting versus UI language: the UI locale is a language identification — it
  carries a region only where a regional variant we take into consideration needs it (the
  zh/pt/es/ar variants); otherwise it stays language-only and its region is never assumed.
  Formatting always needs the region: each `ui-locales.json` entry carries a `formatLocale` that is
  a full tag with a region (the variant's tag for the split locales, a canonical region for the
  rest), and following the system uses the system's full tag when its language matches the UI
  language, otherwise the canonical region. The TUI (item 67) is out of this item's scope; after
  it merges its formatting is integrated here, or its agent gets a prompt if this item lands
  first.
- 2026-11-12: the user approved the formats split into (formats, dates), (formats, numbers/plurals)
  and (formats, times), and approved the format-locale resolution as the next test: a full tag
  carrying a region, the system's tag when it names the same UI locale, the canonical region
  otherwise.
- 2026-11-12: the user confirmed that the Arabic plural test includes the dual: the `two` category
  for count 2, alongside zero/one/few/many/other.
- 2026-11-12: the user approved closing the formats item: every user-visible time string is a
  duration already covered by the numbers slice, and the finish date by the dates slice; all four
  formats lines are ticked.
- 2026-11-12: the user defined the translation standard: every string is compared with a similar
  app/feature's own translation, more popular products scoring higher, then audited for gender
  neutrality and cultural sensitivity; the review record carries the comparisons, ranking and
  audit. The user approves German; for the locales they cannot review, these records and the
  mechanical checks are the gate.
- 2026-11-12: the user approved the revised German ("Much better") and fixed the policy: the
  compared products are evidence for idiomatic wording only; a non-gender-neutral or culturally
  insensitive term is never accepted, even when a product uses it. The product's wording stays in
  the review record as evidence.
- 2026-11-12: French review with the user: queue phases are bare nouns (`Transcription… 42 %`;
  `en train de` was judged not idiomatic for a status label); speaker terms use the epicene
  `voix` throughout, because the app handles the speakers' voices, not the speakers as
  individuals (point médian and doublets rejected for length and screen readers; `personne(s)
  qui parlent` rejected as too long); the rename field and reset button are `Renommer la voix
  {number}` and `Annuler le renommage de la voix {number}`. The user confirmed the French.
- 2026-11-12: the user approved the refactor (one table drives the shipped-catalog tests) and
  confirmed three discovered scenarios: the editor shortcuts follow the translated access keys,
  without collisions; and the shipped-catalog check is driven by `docs/api/ui-locales.json`.
- 2026-11-12: the user approved the German Discard access key `V<u>e</u>rwerfen` (E): `V` would
  take Ctrl+V (paste) from the field, and `R`, `W`, `F` and `N` belong to the browser.
- 2026-11-12: the user approved sharing the access-key reader (refactor) and the
  `ui-locales.json`-driven check as option A: catalogs on disk must be shipped UI locales and pass
  parity; a UI locale without a catalog stays the wave's open work. Option C — the App language
  picker offers only locales with a catalog — was added as a discovered scenario.
- 2026-11-12: the user approved the picker cycle and asked for the catalog wiring (the chosen
  language switches the whole UI) to be part of it.
- 2026-11-12: the user approved the system-language refactor and the Spanish slice ("go").
  Spanish follows the Spanish (Spain) variants and the same policy; the review record flags the
  products' `hablante(s)` as masculine-generic and not adopted in favour of `voz`/`voces`.
- 2026-11-12: the user confirmed the percent issue: the queue phase's percent sign is appended in
  the component (`${percent}%`), not through the catalog; the phase label and its number should
  come from one catalog message and `Intl`. The test-list item stands as planned work.
- 2026-11-12: the user approved the Spanish wording, noting they cannot judge it closely and rely
  on the record and the mechanical gates. The screen-reader reset label keeps
  `Deshacer el cambio de nombre de la voz {number}` ("cambio de nombre" is the natural Spanish;
  the French shape `Annuler le renommage` was not needed).
- 2026-11-12: the user approved the percent test ("go"): the queue phase's percent is formatted
  by the format locale, not appended in the component.
- 2026-11-12: the user approved the Latin American Spanish slice ("go") as the sibling of
  Spanish (Spain); the wording review relies on the records and the mechanical gates where the
  user cannot judge it.
- 2026-11-12: the user cannot review the Latin American Spanish beyond the Spanish (Spain)
  variant; the review record and the mechanical checks are the gate, and the slice is accepted.
- 2026-11-12: the user chose Arabic ("Take Arabic, which I can somewhat review"), which also
  unblocks the RTL item; the wording review follows where they can, with the records and the
  mechanical checks as the rest of the gate.
- 2026-11-12: the user approved the Arabic wording ("Ok, looks good to me").
- 2026-11-12: the user chose the RTL test next ("Ok, looks good to me - the Arabic, the RTL item
  is next").
- 2026-11-12: the user approved the RTL layout test ("go").
- 2026-11-12: the user approved the Radix direction slice ("go").
- 2026-11-13: the user approved the two localization e2e journeys ("go on"); both pass in the user's Playwright run.
- 2026-11-13: the user chose Hebrew for the next wave slice ("Let's do Hebrew"), noting the wave list is stricter than its heading; Hebrew is the user's first language, and they reviewed the translations and approved them.
- 2026-11-13: the user approved the Hebrew translations ("I approve the translations"), including the dot-free `קול`/`קולות` for speakers and `תהליכונים (Threads)`; the review record and the mechanical checks remain the gate.
- 2026-11-13: the user chose Portuguese for the next wave slice ("Let's do portuguese"); the Brazilian variant comes first (pt-BR), with European Portuguese (pt-PT) as its sibling.
- 2026-11-13: the user's review capacity is now very limited; from here every locale must be verified by the agent to the extent possible: (a) terminology compared with the compared products' own wording in that locale, per term in the glossary and per string in the review table; (b) gender neutrality, naming the neutral form and rejecting the gendered alternative; (c) cultural neutrality (no idioms, culture-specific examples or loaded terms); plus an independent reviewer pass over the catalog and record before it is called review-ready. The user's approval is expected only where they can give it.
- 2026-11-13: the user chose Portuguese (Portugal) as the next wave slice ("Let's do protuguese of
  portugal"); pt-PT ships as the sibling catalog of pt-BR (European vocabulary, `a` + infinitive
  progress, no `você`), with its glossary, style guide and review record.
- 2026-11-13: the user rejected the masculine nominal plural as gender-neutral in Hebrew (verbs
  and imperatives do not distinguish gender in the plural; the nominal ־ים suffix does): the
  catalog now uses `קול`/`קולות` for speakers and `הפרדת קולות` for diarization, and the
  glossary and style guide record the rule.
- 2026-11-13: the user ruled that `frontend` never appears in any language; the user-facing term
  is the user interface (`interface do utilizador`, `Interfaz`/`Interface`/`ממשק` in the sibling
  catalogs). The English, German, Brazilian and European Portuguese catalogs were corrected, and
  the tests and e2e selectors follow the new English label.
- 2026-11-13: the user chose Italian ("Do Italian"), the last language of the wave's boundary
  (rank 24, 60 million first-language speakers); it ships with the `voce`/`voci` speaker term and
  the `tu` address, so the wave's boundary set is complete.
- 2026-11-13: the user asked for the Italian translation to be verified ("if everything is fine it
  can be approved"). The agent read all 199 strings and corrected eight: «Modifica non salvata»
  (agreement with «modifica»), «sceglilo» (doubled i), «Nessuna» (agreement with «interfaccia»),
  the settings intro's mixed clause, «vedi la cronologia» (lowercase), «Fallite» aligned with the
  badge «FALLITA», and the audio-position «a {time}» in the two selection labels and the outcome
  line. The review page now marks the 65 phrases that no compared product writes with «—» instead
  of assigning them a product's wording. Italian is approved.
- 2026-11-13: the user cannot review the remaining locales; the agent verifies each one itself,
  with two independent reviewer passes over the catalog and the records. Italian was re-audited
  this way and is approved.

- **Cycle 44 — 2026-11-13 — (wording — independent verification of Italian).** Two independent
  reviewers (copy/terminology and neutrality/documentation) read the 199 strings and the records.
  Both found the same high-severity bug: `{outcome} durante la {phase}` rendered
  «durante la identificazione», ungrammatical; the phase strings now carry their article
  (`l'identificazione delle voci`, `l'elaborazione`), as French and Spanish do. Also fixed: the
  row action `Metti in pausa` (was the noun «Pausa» in a `{verb}` slot), the speaker-mode label
  `Rilevamento delle voci` so its options agree, the seek slider's label, `Errore in {filename}`
  (a file cannot «riuscire»), `concluse` in the empty state (History lists failures too),
  `un'attività già in esecuzione` spelled out, `Fallita` aligned with the badge, and
  `Il rilevamento … non è riuscito`. Two hard-coded English strings in code were found and fixed
  (the transcript's actions label; the speaker names joined with a literal "and"). The review page
  now cites, per row, the term the string reuses or `—`. Gates: 52 files / 335 tests, prettier,
  `tsc`, Vite build, docs `tsc`.

- **Cycle 45 — 2026-11-13 — (wave, tenth slice — Chinese (Simplified)).** Two independent
  reviewers (copy/terminology and neutrality/documentation) read the draft. Fixed from their
  findings: `其发言` instead of the object pronoun `它们` for speakers, `这些合并将永久生效`
  (the merges, not an operation, become permanent), the fallback notice names the device to reuse
  (`如需再次使用该设备`) and reads `现为 CPU`, the settings intro is rewritten (`新上传内容的默认设置`,
  `沿用原设备`), `没有符合搜索和筛选条件的任务`, `无法恢复队列`/`恢复` (resume), `说话人数量上限` for
  max speakers (three keys and the glossary), `前往队列` on the not-found page, the outcome line
  `在 {time} 处{phase}时{outcome}`, `已完成`, and `松开即可上传`. The records were corrected too:
  the glossary's unsupported evidence, the pronoun claim (the character 他 occurs only inside 其他),
  the longest-string list (merge-body 51 characters) and the percent-sign note. `zh-Hans.json`
  (199 keys; no grammatical gender, `说话人`, full-width punctuation, access keys shown as
  `保存（S）`/`放弃（D）`), with `docs/glossary/zh-Hans.md`, `docs/style-guides/zh-Hans.md` and
  `docs/reviews/zh-Hans.md` (199 rows). Gates: 52 files / 335 tests, prettier, `tsc`, Vite build,
  docs `tsc`. The wave has 10 of its 27 UI locales; 17 remain.

- **Cycle 46 — 2026-11-13 — (wave, eleventh slice — Chinese (Traditional)).** Taiwan's catalog,
  separate from the Simplified one: `佇列`, `設定`, `裝置`, `執行緒`, `檔案`, `使用者介面`, `預設`,
  `轉錄`, `音訊`, `說話者`, full-width punctuation with `「」` for a quoted control. Tests: the
  disk-driven parity and access-key checks and the picker check over `src/locales/zh-Hant.json`,
  199 keys. Two independent reviewers read the draft: one found only documentation defects (the
  longest-string claim, the seek evidence, the reassign evidence), fixed; the other found eight
  wording defects, fixed — `繼續` for resume (not `恢復`, which reads as restore), `改用 CPU 的通知`
  (not the calque `退回通知`), `按一下` instead of the Simplified `點擊`, the failed line back in the
  `{filename} …` parallel (`轉錄失敗`), `移至` matching the menu, the fallback instruction naming
  the device to select, and `重新選取該裝置`. `docs/glossary/zh-Hant.md`,
  `docs/style-guides/zh-Hant.md` and `docs/reviews/zh-Hant.md` (199 rows). Gates: 52 files / 335
  tests, prettier, `tsc`, Vite build, docs `tsc`. The wave has 11 of its 27 UI locales; 16 remain.

- **Cycle 47 — 2026-11-13 — (wave, twelfth slice — Hindi).** `hi.json` (199 keys): `प्रतिलेख`/`प्रतिलेखन`,
  `कतार`, `कार्य`, `वक्ता` (masculine, used generically), `वक्ता पहचान`, danda `।`, access keys
  `सहेजें (S)`/`छोड़ें (D)`. The draft was mechanically clean, and the copy reviewer found
  fourteen wording defects, fixed: the settings intro was a headline fragment with no predicate
  (`यहाँ तय होता है कि प्रतिलेखन कहाँ चलेगा और … मान क्या होंगे`), the progress bar announced the
  transcript instead of the process (`प्रतिलेखन`), the merge sentence and the cancel sentence
  gained their subjects (`वक्ताओं को मिलाया गया है`, `इससे कार्य रुक जाता है`), the fallback
  notice names the device once, the device probe is a `जाँच`, the empty state says `समाप्त` (every
  finished task), the upload agrees with `फ़ाइल` (`का अपलोड`), `लंबित कार्य`, the thread errors use
  `पूर्णांक`, `मानों पर सेट`, the reassign hint and speaker select use `असाइन`/`चुनें`, and the
  delete failure is finite (`हटाया नहीं जा सका`). Docs corrected (imperatives, `वक्ता`'s gender,
  invariant options). The test that proved "an unshipped system language is skipped" moved from
  Hindi (now shipped) to Tamil. Gates: 52 files / 335 tests, prettier, `tsc`. The wave has 12 of
  its 27 UI locales; 15 remain.

- **Cycle 48 — 2026-11-13 — (wave, thirteenth slice — Bengali).** `bn.json` (199 keys):
  `প্রতিলিপি`/`প্রতিলিপিকরণ`, `সারি`, `কাজ`, `স্পিকার`, `স্পিকার শনাক্তকরণ` (Bangla Academy
  spelling), danda `।`, Bengali numerals in the validation messages, access keys
  `সংরক্ষণ (S)`/`বাতিল (D)`, the composed row label verb-final (`{filename} {verb}`). Two
  independent reviewers found the copy defects — the cancel sentence got its subject
  (`এতে কাজটি থেমে যাবে`), the outcome position uses `{time}-এ`, the reassign labels ask where
  (`কোথায় সরাবেন…`) and choose (`স্পিকার বেছে নিন`), the fallback notice names the device once,
  `history.retryFailed` became a clause, `ফিল্টার সরান`, `অ্যাকশন` for the menu, `কোনো` (bn-BD),
  plural merges, one pause verb (`বিরত করুন`), and Bengali digits — and the record defects: the
  glossary had quoted wordings the review denied (trimmed to the two products actually cited and
  their recorded wording), a false "not a person" justification for `স্পিকার`, the spacing rule
  (case suffixes hyphenate), the audit column now carries the neutrality result on every row, and
  max-speakers/retry evidence no longer cites wording the strings do not contain.
  `docs/glossary/bn.md`, `docs/style-guides/bn.md`, `docs/reviews/bn.md` (199 rows, 0 stale).
  Gates: 52 files / 335 tests, prettier, `tsc`, Vite build, docs `tsc`. The wave has 13 of its 27
  UI locales; 14 remain.

- **Cycle 49 — 2026-11-13 — (wave, fourteenth slice — Russian).** `ru.json` (199 keys): buttons
  as infinitives (`Сохранить`, `Удалить`), statuses agreeing with `задача`, the genitive phase
  strings (`{outcome} во время {phase}`), four plural branches, `«…»` for quoted controls, access
  keys `Сохранить (S)`/`Не сохранять (D)`. The copy reviewer found the `транскрипт` →
  `расшифровка` correction (Microsoft's Russian UI never says «транскрипт»), the settings intro's
  coordinated fragment, the merge body's number agreement and dative (`Реплики {names} … к спикеру
  {survivor}`), the fallback notice's two «его» (now names the previous device), the broken
  `Изменить порядок {filename}` (now `Переместить {filename}`), the future in the two confirm
  bodies, `Не сохранять`/`Вернуть оригинал`, `под запрос и фильтр`, `на отметке {time}`,
  `Переместить` unified across the flow, `Открыть историю`, lower-case `Назад к истории`,
  `Остановить воспроизведение сегмента`, `Задачи в ожидании`, `Максимальное число спикеров`,
  `Аудиозапись {filename}`, and the cancelled line's parallel (`{filename}: отмена —`). Docs:
  transcript row and the address rule (plural imperative, not "avoid the second person"). Gates:
  199/199 review rows current, 52 files / 335 tests, prettier, `tsc`, Vite build, docs `tsc`. The
  wave has 14 of its 27 UI locales; 13 remain.

- **Cycle 50 — 2026-11-13 — (wave, fifteenth slice — Japanese).** `ja.json` (199 keys):
  `文字起こし`, `キュー`, `タスク`, `話者分離`, `話者`, `既定`, `保存 (S)`/`破棄 (D)`, `。`/`、`/`？`
  with `「」` for a quoted control, only the `other` plural branch. The copy reviewer found the
  state page's clauses (`失敗しました`, `キャンセルされました`, and the outcome lines end with `。`),
  `接続できません` instead of the calque `到達できません`, the settings intro written as a sentence,
  the merge body stating the state (`話者が結合されています`) and permanence in user terms
  (`元に戻せなくなります`), `最大話者数` (the limit modifier first), no space after `？`, one verb
  for the merge (`結合`), `キューのタスク`, the object-first composed label (`{filename} を{verb}`),
  the fallback naming the original device, `キューに入っています`, `通知も閉じられます`,
  `移動させる`, and the format error's `。`. The neutrality audit found the max-speakers evidence
  quoting a different term (now `—`), a self-contradictory question-mark rule, and the em dash set
  with English spaces; the dashes are tight now, and the guide states the `？` and sentence rules.
  Two tests that used Japanese as an *unshipped* locale now use Tamil. Gates: 199/199 rows current,
  52 files / 335 tests, prettier, `tsc`, Vite build, docs `tsc`. The wave has 15 of its 27 UI
  locales; 12 remain.

- **Cycle 51 — 2026-11-13 — (wave, sixteenth slice — Punjabi).** `pa.json` (199 keys):
  `ਟ੍ਰਾਂਸਕ੍ਰਿਪਟ`/`ਟ੍ਰਾਂਸਕ੍ਰਿਪਸ਼ਨ`, `ਕਤਾਰ`, `ਕਾਰਜ`, `ਸਪੀਕਰ`/`ਸਪੀਕਰ ਪਛਾਣ`, `ਡਿਫ਼ਾਲਟ`, danda `।`,
  `ਸੰਭਾਲੋ (S)`/`ਛੱਡੋ (D)`, `one`/`other`. The neutrality audit found the upload agreeing with the
  feminine `ਫਾਈਲ` (now `ਅੱਪਲੋਡ ਹੋ ਰਹੀ ਹੈ`) and the geminated `ਅੱਪਲੋਡ` in two strings, the financial
  `ਬਕਾਇਆ` for pending tasks (now `ਬਾਕੀ`), the untranslated homograph `ਹੋਮ` in the home link (now
  `ਮੁੱਖ ਪੰਨਾ`), and one sentence using both `ਇਸ ਨੂੰ` and `ਇਸਨੂੰ`; the records gained the `<u>` tags
  in the access-key rule, an honest coverage line (`—` means no comparison was found), the
  English-punctuation caveat, a noun note for the reset-merges row, and products trimmed to the
  features the record cites. Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`.
  The wave has 16 of its 27 UI locales; 11 remain.

- **Cycle 52 — 2026-11-13 — (wave, seventeenth slice — Turkish).** `tr.json` (199 keys):
  `transkript`/`transkripsiyon`, `kuyruk`, `görev`, `konuşmacı`/`konuşmacı tanıma`, `varsayılan`,
  `Kaydet (K)`/`Bırak (B)`, `one`/`other`, `kullanıcı arayüzü`. The copy reviewer found the
  composed row label needed a separator (`{verb}: {filename}`), the merge body must not suffix the
  survivor placeholder (`{survivor} ile birleştirilir`), the outcome line gained its subject
  (`Görev, {phase} sırasında {outcome}`) with the two state strings as lowercase predicates
  (`başarısız oldu`, `iptal edildi`), `{filename} başarısız oldu`, `Biten görevler` (History lists
  failures too), `ses bölme aşamasından önce`, the device term unified on `cihaz` (catalog,
  glossary and review row), `Başarısız olanlar`, the `ise` contrast in the settings intro,
  `olabilir` scoped over both clauses, and the standalone status words capitalised. Evidence rows
  that quoted „Yükle“ for restore/load now say `—`, and `settings.device` cites `„Cihaz“`.
  Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`. The wave has 17 of its 27
  UI locales; 10 remain.

- **Cycle 54 — 2026-11-13** (wave, eighteenth slice: Vietnamese). `vi.json` (199 keys, `772355cb`),
  `docs/glossary/vi.md`, `docs/style-guides/vi.md`, `docs/reviews/vi.md` (199 rows). Key decisions:
  `bản chép lời` / `chép lời` (switched from `bản ghi` / `phiên âm` on the neutrality review's
  evidence), `hàng đợi`, `tác vụ`, `người nói`, `nhận diện người nói`, `mặc định`;
  `Lưu (L)` / `Bỏ (B)`; only `other`; `giao diện người dùng` instead of `frontend`; no address
  pronoun at all. Copy review applied: the reassign body reads `đã được hợp nhất`, `các lần hợp
  nhất` and `trở thành vĩnh viễn`, the same occurrence classifier in `resetMerges` and its quote;
  the outcome line restructured to `{outcome} khi đang {phase}` and `… ở mốc {time}` (style guide
  follows); `Chuyển` for the move confirm, `Thao tác với`, `Chưa lưu được`, `Không thể thử lại`,
  and the verb-first settings intro. Gates after the fixes: 52 files / 335 tests, prettier, `tsc`.
  The wave has 18 of its 27 UI locales; 9 remain.
  Neutrality review applied (same cycle, follow-up commit): the transcript and transcription terms
  are Google's `Bản chép lời` / `chép lời`, not `bản ghi` (Microsoft's word for the recording) or
  `phiên âm` (phonetic transcription); evidence rows now carry a wording only where one was seen
  (`—` for speakers, diarization, max speakers, seek, reassign, retry, pause, discard), and the
  review preamble states that rule; the glossary follows the catalog's outcome form and the style
  guide quotes `Đang tải lên {filename}`.

- **Cycle 55 — 2026-11-13** (wave, nineteenth slice: Cantonese). `yue.json` (199 keys),
  `docs/glossary/yue.md`, `docs/style-guides/yue.md`, `docs/reviews/yue.md` (199 rows); `yue` was
  already in `docs/api/ui-locales.json` (粵語, `yue-HK`). Key decisions: interface copy is written
  Chinese in Hong Kong usage, not colloquial Cantonese (no 嘅/咗/喺/唔), the register the Hong Kong
  products use; `轉錄稿` / `轉錄` (Google Meet zh-HK), `上載`, `下載`, `檔案`, `設定` (Apple zh-HK),
  `佇列`, `講者`, `講者識別`, `執行緒`, `使用者介面`, `預設`; full-width punctuation, `「…」`,
  `儲存（S）` / `捨棄（D）`; only `other`. Copy review applied: `主題` for the theme toggle, the
  reassign body (`講者已經合併`, `將永久生效`), `其發言` instead of the gendered `他們`,
  `停止播放段落`, `放開即可上載`, `重新選取該裝置`, `改用 CPU 的通知`, the terminal `。` on
  `history.noMatches`, and the longest-string claim. Neutrality review applied: Microsoft dropped
  from the compared products (no row cites it; Google 1, Apple 2), the play/pause and
  `轉錄功能` citations reduced to `—`, the badge/filter claim and the dash rule scoped to what the
  catalog does. Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`. The wave has 19
  of its 27 UI locales; 8 remain.

- **Cycle 56 — 2026-11-13** (wave, twentieth slice: Marathi). `mr.json` (199 keys),
  `docs/glossary/mr.md`, `docs/style-guides/mr.md`, `docs/reviews/mr.md` (199 rows). Key decisions:
  imperatives are gender-unmarked (`रद्द करा`, `हटवा`), sentences about the neuter `कार्य` agree in
  the neuter, `वक्ता`/`वक्ते` is common gender, sentences end with `.` as Apple's Marathi does,
  `जतन करा (S)` / `टाकून द्या (D)`, plurals `one` and `other`. Copy review applied: `{time} ला`
  for media positions (not `वाजता`), passive `अपलोड होत आहे`, `चा क्रम बदला`, `CPU वर`, plural
  `भाषणे … येतील` and `विलीनीकरणे … कायमची होतील`, `फाइलच्या नावाने`, `तपासणी`,
  `कनेक्ट होऊ शकले नाही`, `थ्रेड्सची संख्या`, `डीफॉल्ट सेटिंग्ज`, the doubled `तो` removed, and
  `त्याचा डिव्हाइस`. Neutrality review applied: Apple's `mr-in` articles are fully Marathi, so
  their wording is now recorded in the rows it covers (सेटिंग्ज, डिव्हाइस, डाउनलोड, रद्द करा,
  हटवा, प्ले करा, शोधा, पर्याय, सूचना, निवडा) and the glossary follows; `transcript.reassign.title`
  reads `वक्ते बदलायचे?` (the masculine `बदलायचा` marked the speaker); every audit cell carries the
  cultural-sensitivity result; the punctuation rule is scoped to sentences; the length counts are
  current. Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`. The wave has 20 of
  its 27 UI locales; 7 remain.

- **Cycle 57 — 2026-11-13** (wave, twenty-first slice: Telugu). `te.json` (199 keys),
  `docs/glossary/te.md`, `docs/style-guides/te.md`, `docs/reviews/te.md` (199 rows). Key decisions:
  respectful plural imperative (`రద్దు చేయండి`), neuter agreement for `టాస్క్`, `వక్త`/`వక్తలు` and
  the neutral plural `వారి`, sentences end with `.`, `సేవ్ చేయండి (S)` / `విస్మరించండి (D)`,
  plurals `one` and `other`. Evidence: Google's Telugu account page („సెట్టింగ్లు“, „సేవ్“) and
  Apple's `te-in` articles („డివైజ్“, „డౌన్లోడ్“, „తొలగించండి“, „ఎంచుకోండి“, „ఫైల్స్“) are
  recorded where the same word appears; Microsoft redirects `te-in` to English. Copy review
  applied: `మీ శోధనకు, ఫిల్టర్కు`, `అన్నిటికంటే` for the top/bottom moves, `కొనసాగించలేకపోయాం`,
  `ఎక్కడికి తరలించాలి`, `{filename}: {verb}`, `{names} వక్తలను` (the accusative cannot attach to
  a placeholder), the imperative revert, the intro's `-ఓ` clauses, the descriptive cancel body,
  `కనుగొనబడలేదు`, `అప్లోడ్లు`, `అధునాతన`, `ఎంపికలు`, `స్థిర`, `ఆదేశం`, `తీసివేయండి`. Neutrality
  review applied: Google added to the compared products and the settings rows cite it, the
  "only product" claim removed, the length claim narrowed to the longest strings. Gates: 199/199
  rows current, 52 files / 335 tests, prettier, `tsc`. The wave has 21 of its 27 UI locales; 6
  remain.

- **Cycle 58 — 2026-11-13** (wave, twenty-second slice: Korean). `ko.json` (199 keys),
  `docs/glossary/ko.md`, `docs/style-guides/ko.md`, `docs/reviews/ko.md` (199 rows). Key decisions:
  noun/stem labels for buttons, 해요체 for instructions, particle pairs for placeholders
  (`을(를)`, `(으)로`), ASCII punctuation, `저장 (S)` / `버리기 (D)`, only `other`. Evidence: Google's
  Korean Meet help is fully translated, and its wording is recorded where a comparable control or
  term was found (`스크립트`, `스크립트 작성`, `설정`, `재설정`, `기기`, `파일`, `저장`, `취소`,
  `삭제`, `닫기`, `열기`, `계속`, `병합`). Copy review applied: `을(를)` in the cancel/delete titles,
  `{filename} {verb}`, `{filename}에 대한 작업`, the completed link clause, the settings intro's
  device sentence, the drop overlay's predicate and `끌어다 놓기`, the resume control paired with its
  error, the genitives (`{filename}의`, `화자 {number}의`), `이동할 화자`, the merge body without the
  repeated target, the reassign consequence, and `…지 못했습니다` for failed actions. Neutrality
  review applied: the product name stays `Speech-to-Text`; pause rows no longer cite `중지` (Google
  never uses it for pause) and are `—`; reset rows cite `재설정`; the coverage rule is narrowed to
  comparable controls; the glossary's "only product" claim is scoped to this cycle; the particle
  rule names the object case. Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`.
  The wave has 22 of its 27 UI locales; 5 remain.

- **Cycle 59 — 2026-11-13** (wave, twenty-third slice: Tamil). `ta.json` (199 keys),
  `docs/glossary/ta.md`, `docs/style-guides/ta.md`, `docs/reviews/ta.md` (199 rows). Key decisions:
  plain singular imperatives for control labels, `-வும்` for text actions and instructions,
  `அவர்களின்` as the neutral plural, sentences close with `.` where the English does, `சேமி (S)` /
  `நிராகரி (D)`, `one` and `other`. Evidence: Apple's `ta-in` iPhone/iPad user guides are fully
  Tamil and are the only product compared (`அமைப்புகள்`, `சாதனம்`, `பிறக்க`, `நீக்கு`,
  `தேர்ந்தெடு`, `கோப்புகளை`, `வரலாற்றைப் பார்வையிடுதல்`, `மீட்டமை`). Copy review applied: the
  accusative kept off `{names}` (`ஆகியோரை`), `தேடலுக்கும் வடிகட்டிக்கும்`, `{filename} {verb}`,
  the sandhi forms (`இயல்புநிலைப்`, `கோப்புப்`, `தேர்ந்தெடுக்கக்`), `இவற்றை நகர்த்து…`,
  `நகர்த்த வேண்டிய இடம்`, `இணைவு` for merges, `செயல்நீக்கலாம்`, imperative retries, the count
  form of max speakers, `மேம்பட்ட விருப்பங்கள்`, `இயக்க முறைமை`/`ஒளி`, the verbal probe failure,
  `மூலத்திற்குத் திரும்பு`, and the clause `;`. Neutrality review applied: the review lists only
  the compared product, the evidence source is named as the user guides, the `—` definition, the
  history and reset evidence, and the length counts. Shipping the catalog flipped the picker
  tests' "no catalog yet" example from Tamil to Indonesian (`id`), which keeps the same assertion.
  Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`. The wave has 23 of its 27 UI
  locales; 4 remain.

- **Cycle 60 — 2026-11-13** (wave, twenty-fourth slice: Urdu). `ur.json` (199 keys),
  `docs/glossary/ur.md`, `docs/style-guides/ur.md`, `docs/reviews/ur.md` (199 rows). Key decisions:
  right-to-left, plain imperatives, `؟` for questions and `،`/`؛` inside sentences with `.` closing
  a sentence (the Arabic catalog's pattern), `محفوظ کریں (S)` / `نظر انداز کریں (D)`, `one` and
  `other`. Evidence: Google's Urdu account page is the only product compared („ترتیبات“، „محفوظ“،
  „حذف“); Apple's `ur-pk` redirects to English. Copy review applied: `باری` for a turn (not
  `گفتگو`), `قطعے`, `انضمام` for the countable merge (button and every quote), `کارروائیاں` for
  actions, the intro's predicate and agreement, `ختم شدہ`, `تعاون یافتہ`, `پیش رفتہ`,
  `منتقل کرنے کی جگہ`, `{filename} {verb}`, the verbal outcomes, the `میں` navigations,
  `دستیاب نہیں تھا`, `صفحۂ اول`, `ناکام رہی`, `تاریخ کے صفحے`, and the drop hint's object.
  Neutrality review applied: the punctuation rule now states the catalog's convention, the review
  lists only the compared product, and the glossary records `باری`, `ضم`/`انضمام`,
  `کنکشن`/`منسلک`, Google's `آلہ` for device, and the speaker-term decision (`اسپیکر`, with `آواز`
  recorded as the epicene alternative). Gates: 199/199 rows current, 52 files / 335 tests,
  prettier, `tsc`. The wave has 24 of its 27 UI locales; 3 remain.

- **Cycle 61 — 2026-11-13** (wave, twenty-fifth slice: Indonesian). `id.json` (199 keys),
  `docs/glossary/id.md`, `docs/style-guides/id.md`, `docs/reviews/id.md` (199 rows). Key decisions:
  bare-verb labels, imperative instructions, uppercase status badges with sentence-case labels,
  `Simpan (S)` / `Buang (D)`, only `other`. Evidence: Google's Indonesian account page is the only
  product compared („Setelan“, „perangkat“, „tugas“). Copy review applied: `berakhir` (not
  `selesai`) before chunking, `dapat diurungkan` for undo, `Perubahan yang disimpan`, `Tugas dalam
  antrean`, `Percobaan ulang gagal`, the completed row leading with `{filename}`, and the
  fallback instruction's object. Neutrality review applied: the badges are uppercase, the spacing
  audit notes describe the actual strings, the Address bullet drops the `Anda` claim, the settings
  citation keeps its case, `memiliki` for the formal possessive, `Pindahkan` for the transitive
  move, and `move`/`undo` rows added to the glossary. Shipping the catalog moved the picker tests'
  "no catalog yet" example from Indonesian to Javanese (`jw`), same assertion. Gates: 199/199 rows
  current, 52 files / 335 tests, prettier, `tsc`. The wave has 25 of its 27 UI locales; 2 remain.

- **Cycle 62 — 2026-11-13** (wave, twenty-sixth slice: Javanese). `jw.json` (199 keys),
  `docs/glossary/jw.md`, `docs/style-guides/jw.md`, `docs/reviews/jw.md` (199 rows). Key decisions:
  krama register with `-aken` (never `-ake`), `mlampah`, `pados`, `mriki`, `inggil`/`ngandhap`,
  `saderengipun`, `bikak`, `dipunleren`; the `owah` stem kept in `Owahi`/`Ngowahi` and recorded;
  `Simpen (S)` / `Buang (D)`; only `other`. Evidence: Google's Search interface is the only
  Javanese product UI found („Setelan“, „Basa“; account and help pages fall back), with the probe
  URLs in the review. Copy review applied: `Antrian mboten saged dipunleren`, `Terusaken`,
  `Batalaken`, `Pindahaken dhateng inggil`/`ngandhap`, `ing mriki`, `bikak`, `Owahi segmen`,
  `Kaca utama`, `Mboten saged nyoba malih`, `dipuntranskripsi`, `dipunwangsulaken`,
  `kawiwitan`, `Pamuter`, `Parentah`, `MLAMPAH`, `mlampah`, and the descriptive cancel body.
  Neutrality review applied: the Google Search evidence and probe URLs, `Pados`, `saderengipun`,
  the krama verbs for running, `Kaca ngarep` → `Kaca utama`, the loan list, the glossary rows for
  edit/open/search/home/command/up-down/pause-queue, and the review's ranked product list. Shipping
  the catalog moved the picker tests' "no catalog yet" example from Javanese to Persian (`fa`).
  Gates: 199/199 rows current, 52 files / 335 tests, prettier, `tsc`. The wave has 26 of its 27 UI
  locales; 1 remains.

- **Cycle 63 — 2026-11-13** (wave, twenty-seventh and last slice: Persian). `fa.json` (199 keys),
  `docs/glossary/fa.md`, `docs/style-guides/fa.md`, `docs/reviews/fa.md` (199 rows). Key decisions:
  right to left, action labels as verbal nouns or infinitives with formal imperative instructions,
  Persian `،` `؟` `؛` with `.` closing a sentence, `«…»` quotes, Persian digits in validation text,
  `ذخیره (S)` / `دور انداختن (D)`, `one` and `other`. Evidence: Google's Persian account page is
  the only product compared („تنظیمات“، „دستگاه“، „ذخیره“، „حذف“). Copy review applied: the
  plural merge verb, `عملیات {filename}`, `مکث` for pause (with `توقف` kept for stop),
  `انتقال به بالای فهرست`, `هیچ‌کدام`, the perfect copula, `ناموفق شد`, the max-speakers error
  naming its field, the device fallback sentence, `در حین`, the failure toasts, `تکمیل‌شده`,
  `جست‌وجو`, the `نوبت` selection clauses, `بازگردانده شود`, the reassign comma, the intro's
  predicate, `دستور ترمینال`, the not-found copula, and Persian digits. Neutrality review applied:
  the formal `انتخاب کنید` (no clitic), the Address rule's pronoun claim, the `اتصال`/`متصل`
  glossary row, and the two stale audit cells. Gates: 199/199 rows current, 52 files / 335 tests,
  prettier, `tsc`. All 27 wave locales ship; the remaining test-list lines are the review page and
  the confirmations of wording, RTL, wave and must-not-break.

- **Cycle 64 — 2026-11-13 — (review page).** Test: `docs/check-reviews.test.ts` — a sandbox whose
  German page carries one stale translation (`app.queue` → `FALSCH`) runs
  `node docs/check-reviews.ts` and expects exit 1 with `docs/reviews/de.md:<line>: "app.queue"` and
  the stale value in stdout. Red at `0cf2619d`: the script did not exist, stdout empty,
  `AssertionError … did not match /docs\/reviews\/de\.md:\d+: "app\.queue"/`. Green: this commit —
  `docs/check-reviews.ts` checks that every shipped catalog has a page, every key appears once, no
  key is unknown, and each translation equals the catalog's; `docs/checks.test.ts` asserts its
  green line over the tree (`docs/reviews: 27 pages match their catalogs`), and
  `docs/package.json` runs it in `pnpm --dir docs test`. Making the tree pass repaired eight stale
  pages (ar, de, es-419, es-ES, fr, he and pt-BR had duplicate tables, pt-PT was a delta page) and
  `CONTRIBUTING.md` names the path for proposing a translation change. Gates: `tsc -p
  docs/tsconfig.json`, docs prettier, every docs check and its tests (45), frontend 335 tests.
  Follow-up `Fix: read tsc 7's coloured diagnostics`: the user's `pnpm --dir docs test` showed
  `type-check.test.ts` failing because tsc 7.0.2 prints `check-links.ts:91:7 - error TS2322` with
  ANSI colours when colour is enabled, where the assertion expected `check-links.ts(91,7): error
  TS2322`; the test now strips the escape codes and reads either format.

- **Cycle 65 — 2026-11-13 — (review fix 1: the editor labels split by inline-flex).** Test:
  `frontend/src/features/history/TaskTranscriptView.test.tsx` — opening the segment editor, the
  Save and Discard buttons' label is one node (`childNodes` length 1) with the whole text, because
  the Button is an inline-flex row and a label rendered as a fragment becomes two flex items
  ("S  ave", read aloud by Chromium as "S ave"). Red at `6d752d56`: `AssertionError: expected <u></u>`
  (two child nodes). Green: this commit — each `FormattedMessage` is wrapped in a `<span>` again.
  Gates: frontend 336 tests, prettier, `tsc`. No refactoring suggested: the wrapper is two lines at
  the only two rich-message labels inside a flex control, and extracting a component for it would
  abstract one call shape.

- **Cycle 66 — 2026-11-13 — (review fix 2: the transcript keeps its own direction).** Test:
  `frontend/src/features/history/TaskTranscriptView.test.tsx` — the turn's reading text carries
  `dir="auto"` (`segment.closest('[dir="auto"]')` is the turn, not the editor field), so an RTL app
  language cannot mirror an English transcript. Red at `a88e66d8`: `AssertionError: expected null
  not to be null`. Green: this commit — `<TurnText dir="auto">`; the Arabic journey gains the
  browser check the user runs (`dir="auto"` on the turn, computed direction `ltr` inside the RTL
  shell). Gates: frontend 337 tests, prettier, `tsc`; e2e 55 lib tests, `check-playwright`,
  prettier, `tsc`. Refactoring: none suggested — one attribute on the element that holds exactly
  the turn's text; item 76 refines per-item direction later.

- **Cycle 67 — 2026-11-13 — (review fix 3, first slice: the review rows' evidence).** Test:
  `docs/check-reviews.test.ts` — a page row whose Compared cell names a product with a rank and no
  wording (`Google 1`) fails `docs/check-reviews.ts` with page, line and key; the rule accepts every
  quote style the pages use. Red on the tree: 1,019 such rows. Green: 762 bare names are now `—`
  (no wording recorded), and pt-PT records Apple's Portuguese (Portugal) wording on 19 rows
  („Definições“, „dispositivo“, „descarregar“, „ficheiros“, „apagar“); Google's `hl=pt-PT` is
  Brazilian Portuguese, so pt-PT does not cite it. The (wording) line is unticked until the fill
  completes (Approval log, same date). Gates: `check-reviews` green over 27 pages, its two tests,
  docs prettier, `tsc`.

- **Cycle 68 — 2026-11-13 — (review fix 3, pt-PT slice).** Evidence: Microsoft's
  `support.microsoft.com/pt-pt` Teams transcript page adds „transcrição“, „Transferir“, „Eliminar“,
  „orador“, „Parar“; with Apple's „Definições“, „dispositivo“, „descarregar“, „ficheiros“,
  „Apagar“ the pt-PT page now records a wording on 58 of its 199 rows (the other 141 stay `—`,
  no wording recorded). Google is not cited for pt-PT — `hl=pt-PT` serves Brazilian Portuguese —
  and the page's products list says so. Gates: `check-reviews` green, its tests, docs prettier,
  `tsc`.

- **Cycle 69 — 2026-11-13 — (review fix 3, pt-BR slice).** Evidence: Google's `hl=pt-BR` Meet page
  („Transcrição“, „dispositivo“, „arquivo“, „Parar“), Microsoft's `pt-br` Teams page
  („transcrição“, „Baixar“, „Excluir“, „locutor“) and Apple's `pt-br` support
  („Ajustes“, „Apagar“, „Reproduzir“); 55 of the pt-BR page's 199 rows now record a wording, the
  rest stay `—`. The page's products list carries the verified wordings and keeps the gendered
  „locutor“ as evidence only. Gates: `check-reviews` green, its tests, docs prettier, `tsc`.

- **Cycle 70 — 2026-11-13 — (review fix 3, Hebrew slice).** Evidence: Google's Hebrew Meet help
  („תמלול“, „הגדרות“, „קובץ“, „הורדה“, „עצירה“, „הפעלה“, „חיפוש“, „מכשיר“); 25 of the Hebrew page's
  199 rows now record a wording, the rest stay `—`, and the page notes that the catalog keeps
  `התקן` where Google writes `מכשיר`. Gates: `check-reviews` green, its tests, docs prettier, `tsc`.

- **Cycle 71 — 2026-11-13 — (review fix 3, Simplified Chinese slice).** Evidence: Google's
  zh-Hans Meet help („转写“, „设置“, „设备“, „文件“, „停止“, „搜索“, „暂停“); 5 further rows of the
  zh-Hans page now record a wording (the page already carried Microsoft/Apple wordings on most
  rows), 67 stay `—`. Gates: `check-reviews` green, its tests, docs prettier, `tsc`.

- **Cycle 72 — 2026-11-13 — (review fix 3, Traditional Chinese slice).** Evidence: Google's
  zh-Hant Meet help („轉錄“, „設定“, „裝置“, „檔案“, „停止“, „搜尋“, „暫停“); 3 further rows of the
  zh-Hant page record a wording (the page already carried wordings on most rows), the rest stay
  `—`. Gates: `check-reviews` green, its tests, docs prettier, `tsc`.

- **Cycle 73 — 2026-11-13 — (review fix 3, German slice).** Evidence: Google's German Meet help
  („Transkript“, „Gerät“, „Suche“, „pausieren“, „herunterladen“, „beenden“); 5 of the German page's
  43 bare rows now record a wording, 38 stay `—` until a product page supplies theirs. Gates:
  `check-reviews` green, its tests, docs prettier, `tsc`.

- **Cycle 74 — 2026-11-13 — (review fix 3, Russian slice).** Evidence: Google's Russian Meet help
  („расшифровка“, „настройки“, „устройствах“, „файл“, „Остановить“, „Поиск“, „Скачайте“,
  „Приостановить“); 9 of the Russian page's 127 `—` rows now record a wording, 118 stay `—`.
  Gates: `check-reviews` green, its tests, docs prettier, `tsc`.

- **Cycle 75 — 2026-11-13 — (review fix 3, definition and re-tick).** Decision (user, same date):
  `—` is the recorded outcome where best effort finds no comparable product feature or wording; the
  check keeps rejecting a product name without its wording. The per-locale evidence fills of cycles
  67-74 stand, `(wording)` is ticked under that definition, and the remaining `—` rows are the app's
  own vocabulary. Gates: `check-reviews` green, its tests, docs prettier.

- **Cycle 76 — 2026-11-13 — (review fix 4a: the review page's English source cell).** Test:
  `docs/check-reviews.test.ts` — a page row whose English source cell is stale (`Queue (old)`) fails
  `docs/check-reviews.ts` with page, line, key and both texts. Red at `ec38b915`: the check compared
  only the translation, so the planted row passed. Green: the check compares the source cell with
  `en.json`, and the 14 stale rows the review named (seven pages, `settings.defaultFrontend` and
  `settings.frontendsLoadFailed`) are corrected. Gates: `check-reviews` green over 27 pages, its
  three tests, docs prettier, `tsc`.

- **Cycle 77 — 2026-11-13 — (review fix 4b: the review page's glossary column).** Test:
  `docs/check-reviews.test.ts` — a page row whose Glossary cell is wrong (`FALSCH`) fails
  `docs/check-reviews.ts` with page, line, key and the glossary's terms. Red at `e65bb8b8`: the
  column did not exist. Green: every one of the 27 pages and 5,373 rows carries the glossary terms
  its translation uses (computed from `docs/glossary/<code>.md`), and the check keeps the column
  equal to the glossary and the catalog; `CONTRIBUTING.md` names it. Gates: `check-reviews` green,
  its four tests, docs prettier, `tsc`.

- **Cycle 78 — 2026-11-13 — (review fix 5: a stored App language that ships no catalog).** Test:
  `frontend/src/shared/i18n/uiLocales.test.ts` — `effectiveUiLocale('constructor', ['de-DE'])`,
  `'toString'` and `'xx'` all answer `'de'`. Red at `ffce1ed3`: `expected 'constructor' to be 'de'`
  (the `in` operator accepts prototype keys, and `formatLocaleFor` then threw during render).
  Green: the three catalog lookups use `Object.hasOwn`. Gates: frontend 338 tests, prettier, `tsc`.

- **Cycle 79 — 2026-11-13 — (review fix 6: the write-gate tooltip in the app language).** Test:
  `frontend/src/app/writeGate.test.tsx` — with the app language German and the stream cut, the
  drop zone's title equals `de['error.transport-read']`. Red at `3fec4d2c`: the control still named
  the English constant. Green: `useWriteControl` reads `error.transport-read` through `useIntl`,
  and the English constant is gone. Gates: frontend 339 tests, prettier, `tsc`.

- **Cycle 80 — 2026-11-13 — (review fix 7: the progress bar's percentage).** Test:
  `frontend/src/shared/ui/ProgressBar.test.tsx` — under `fr-FR` the bar shows Intl's `42 %` (the
  locale's own space before the sign), not a hand-appended `42%`. Red at `72afc886`: the bar
  rendered `{percent}%`. Green: `ProgressBar` formats through `useIntl().formatNumber(value, {
  style: 'percent' })`, as the queue phase does. Gates: frontend 340 tests, prettier, `tsc`.

- **Cycle 81 — 2026-11-13 — (review fix 8: the transcript control's physical margin).** Test:
  `frontend/src/rtl.test.ts` — the physical-property scan now includes
  `TranscriptTurns.tsx`. Red at `1574d317`: the scan reported `margin-left` on line 116. Green:
  `margin-inline-start`, so the control's spacing mirrors with the reading direction. Gates:
  frontend 340 tests, prettier, `tsc`.

- **2026-11-13 — (review fix 9: the RTL test's claim).** The error callout's comment claimed a
  browser journey checked its rendered side; none does. The comment now says the styled source is
  the evidence and that no journey renders the callout in an RTL locale. No behavior change;
  `rtl.test.ts` passes, prettier and `tsc` green.

- **Cycle 82 — 2026-11-15 — (review fix 10: the review page's generator).** Test:
  `docs/check-reviews.test.ts` — a page whose evidence rows are reordered, though every derived
  cell is right, fails `node docs/check-reviews.ts` with `docs/reviews/de.md: not its generated
  form`. Red on the tree at `f3999fd3`: the check had no generated form to compare. Green: this
  commit — `docs/review-page.ts` parses a page (its prose, its language column, its per-string
  compared/audit cells) and renders it over `en.json`, the catalog and the glossary, padding CJK
  columns by display width; `docs/generate-reviews.ts` rewrites all 27 pages, `docs/check-reviews.ts`
  fails when a committed page is not what it writes, and `CONTRIBUTING.md` names the regeneration
  step before the checks. Gates: check-reviews green over 27 pages, its five tests, docs prettier,
  `tsc`.

- **Cycle 83 — 2026-11-15 — (the review's small findings).** The dead `failureMessage()` is gone
  (its rule lives in `failureLine()`, and the done spec says so); the unreachable
  `case 'appLanguage'` arms are removed; the es-419 glossary points at its own review; the pt-BR
  glossary's transcript phases are nouns; pt-BR and pt-PT quote the reset-merges label with `“…”`;
  the Hebrew device-fallback notice names `CPU` once; the Portugal, Italian and Simplified Chinese
  pages carry their own headings; `docs/known-issues.md` loses the fixed tsc-7 row; the write-gate
  test reads its English text from `en.json`; the German journey sets English first, so it no
  longer depends on the shared backend's stored language. Content edits are covered by the catalog
  checks and the regenerated pages; the journeys are the user's run. Gates: frontend tests,
  prettier, `tsc`; docs checks.

- **2026-11-15 — (the user's full journey, dev mode).** The suite run with the task's own dev-mode
  backend command set by hand, because `./gradlew e2e` cannot run on NixOS (its managed Node is a
  generic-Linux binary): 62 passed, 1 skipped (macOS Metal), 7m27s — the German and Arabic journeys
  and both item-87 specs included, with the frontend rebuilt from its sources.
