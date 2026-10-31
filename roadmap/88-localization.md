# 88 — Localization: the app's own language, in every language whisper transcription supports

Status: spec-approved
Depends on: [74](../docs/specs/desktop-presentation.md) (font coverage for every script, done)
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
  Brazilian and European, `es` into Spain and Latin American, `ar` into its variants; a mapping
  connects a UI locale to the transcription code whisper receives.
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

## Test list (approved 2026-10-31; behaviors only)

- [ ] (setting) "App language" on the Settings page lists the shipped UI locales with their own
      names, stores the choice in the database, and a restart keeps it; a fresh database follows the
      system's language when it is one of them and English otherwise
- [ ] (catalog) Every user-visible string of the frontend comes from the message catalog: labels,
      buttons, `aria-label`s, titles, status words, empty states, confirmations — a check fails on a
      literal string outside the catalog, with the never-translated items listed (product name, file
      extensions, model names, whisper language codes)
- [ ] (catalog) For every shipped locale a check fails on a missing key, a changed placeholder, an
      invalid ICU message, or a missing plural category the locale requires
- [ ] (errors) A refused action (a server error code) shows the catalog's message for that code in
      the chosen language; a code the catalog does not know shows the server's English text
- [ ] (formats) In a locale, dates, times, numbers and plurals follow that locale's rules (for
      example Arabic's plural categories), from the catalog's ICU messages and `Intl`
- [ ] (wording) Each shipped locale's review record names, for the terms that matter, the products
      compared and the wording each uses — the evidence that the term is acceptable; a
      native-speaker opinion is recorded where one was offered, and its absence blocks nothing
- [ ] (review page) Every shipped locale has a committed page generated from its catalog: each
      string with its source text and translation, its glossary terms and the products behind them;
      a check fails when the page is stale against the catalog, and `CONTRIBUTING.md` names the path
      for proposing a change (edit the catalog, run the checks, open a pull request)
- [ ] (rtl) With an RTL app language the layout mirrors: navigation, rows, the speakers panel, the
      player and the settings form, with direction-aware icons; the transcript keeps its own
      direction per item 76, and a mixed-direction transcript stays readable
- [ ] (wave) The first wave is the 24 whisper codes recorded in "Open questions" (every language at
      or above Italian's count), each shipped with its variants; adding the next language touches
      only its catalog, its glossary entry and its review record
- [ ] (e2e, ADR 0046) The journey runs pinned to English and stays green; a second journey runs the
      upload → transcript → speakers → settings path in one wave-1 locale, and one in an RTL locale
- [ ] (must not break) The English UI reads as it does today, the existing frontend, backend and
      journey suites stay green, and switching languages never reloads or loses history or settings

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
  established products, recorded per term. Test list approved as amended; status set to
  spec-approved.
