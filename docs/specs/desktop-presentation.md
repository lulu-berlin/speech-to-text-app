# 74 — Desktop presentation: font coverage for every language, Japanese glyphs, and an app icon with a desktop entry

Status: done (roadmap item 74, approved 2026-10-30)
Depends on: —
Blocks: —

## Goal

Every language the app accepts for transcription renders its script in the self-contained desktop
bundle — no missing glyphs — Japanese transcripts use Japanese glyph forms, and each desktop package
carries the app's icon and a `.desktop` entry so the menu and the running window show one app.

## Evidence

- **15 scripts lack any font in the bundle**: `nix/desktop-bundle.nix:35-48` ships IBM Plex Sans and
  the Plex families for Arabic, Devanagari, Hebrew, Thai, Chinese SC/TC, Japanese and Korean (27
  files, +32.9 MB compressed / +69.4 MB unpacked), but there is no Plex family for Tamil, Malayalam,
  Telugu, Bengali/Assamese, Kannada, Armenian, Gurmukhi, Sinhala, Khmer, Georgian, Gujarati,
  Ethiopic (Amharic), Lao, Myanmar and Tibetan, nor for Hausa's `ƙ ƴ Ɗ Ƙ Ƴ` and Lingala's `Ɛ Ɔ`
  (ADR 0053 records the gap). The `.deb` and the Nix package use the host's fonts; the bundle does
  not.
- **Japanese renders in Simplified Chinese forms**: the UI never marks a transcript's language
  (`frontend/index.html:2` is `lang="en"`), and the bundle's fallback order is SC → TC → JP
  (`nix/desktop-bundle.nix:60-63`), so fontconfig picks the SC face for kanji and kana.
- **No app icon or desktop identity on Linux and macOS**: the MSI target has `icon: icon.png`
  (`desktop/electron-builder.yml:107`, the favicon raster at 512×512, file verified 2026-10-28), but
  nothing else does — the `.deb`, the Nix package and the bundle declare no icon, no
  `desktopName`/`syncDesktopName` and no `.desktop` entry; the user's 2026-10-15 `pnpm --dir desktop
  dist` warned about Electron's default icon and that desktop environments may not link the window
  to the menu entry.
- Item 70 replaces the Electron shell with Slint and rewrites the packaging; the icon, the desktop
  entries and the font set must be carried into that packaging (a requirement on its implementation,
  not a dependency, so this item can land first and its test list guards the result).

## Proposal

1. Add per-script Noto fallback families for the missing scripts and the two extra Latin letters,
   keeping Plex as the UI face; record the closure/payload delta for the bundle as item 50 measured
   fonts.
2. Store whisper's detected language with the task and mark it on the rendered transcript, falling
   back to the requested language, so fontconfig picks the JP/TC/Hans face; keep the app shell's own
   `lang="en"`.
3. Add the favicon-mark app icon and `desktopName` + `linux.syncDesktopName` to the packages, and
   `.desktop` entries for the Nix package and the bundle; verify one window/menu identity.
4. Note in item 70's implementation that the Slint window must keep the fonts, language marking and
   desktop entries.

## Decided (consultation, 2026-10-28)

- **Per-script Noto fallbacks**: add Noto families for the 15 uncovered scripts (three weights, the
  weights the UI uses) plus the Latin extended letters for Hausa/Lingala, keep IBM Plex Sans as the
  UI face, and record the compressed/unpacked delta the way item 50 measured fonts. Host fonts stay
  out of the bundle.
- **The transcript's `lang` comes from whisper's detected language**, stored as a nullable task
  column and exposed in the task JSON, with the requested language as the fallback when detection
  produced none; whisper-cli's JSON carries a `result` object next to `transcription` (the pinned
  1.8.4 binary's strings, checked 2026-10-28), so the first cycle verifies the field and reports if
  it is absent. Auto-detected Japanese therefore marks `ja`.
- **The icon is the existing favicon mark** (`frontend/src/shared/brand/favicon.svg`, rasterized to
  512 PNG and derived `.icns`/`.ico`); one `.desktop` file and icon set is shared by the `.deb`, the
  Nix package and the bundle, and the MSI keeps the same artwork.
- **The bundle installs its menu entry only when asked** (the user, 2026-10-29): it has no install
  step, so `stt-app-bundle --install-desktop-entry` writes the shared `.desktop` entry (Exec = the
  bundle file's own path) and the icon into `${XDG_DATA_HOME:-~/.local/share}`, and
  `--remove-desktop-entry` deletes them; nothing is written otherwise. The entry and icon travel
  inside the launcher script, so the shared icon set is the favicon SVG as the scalable `hicolor`
  icon.

## Test list (approved; behaviors only)

- [x] Every language in `docs/api/languages.json` renders its sample text in the bundle with no
      missing glyphs (a font-coverage check over the shipped font set)
- [x] A `ja` transcript renders Japanese glyph forms; `zh-Hans` and `zh-Hant` stay distinct
- [x] An `auto`-uploaded task stores whisper's detected language, the API exposes it, and its
      transcript marks that language; a task whose detection is empty falls back to the requested
      language
- [x] (discovered) whisper-cli's `result.language` is parsed as the detected language, and
      `params.language` (the requested one) is never mistaken for it
- [x] (discovered) A task whose exposed language is `auto` (requested auto, nothing detected) never
      marks its transcript with an invalid `lang` value: the region carries a valid tag or none
- [x] (discovered) A transcript marked with whisper's own Chinese codes resolves to a definite Han
      face: `zh` to the Simplified face, `yue` to the Traditional one
- [x] The `.deb` installs a menu entry with the app icon and `desktopName`, and the running window
      is associated with it (one app identity)
- [x] The Nix package and the bundle carry matching `.desktop` entries and icon
- [x] (discovered) The bundle's `--remove-desktop-entry` deletes exactly the entry and icon that
      `--install-desktop-entry` wrote
- [x] (discovered) A bundle stored under a path with spaces or shell-special characters installs an
      entry whose Exec still launches it (the `.desktop` spec splits an unquoted Exec on spaces)
- [x] (discovered, review 2026-10-29) The Nix package and the bundle install the `.deb`'s identity
      — the entry file named `app.stt.desktop.desktop` with `StartupWMClass=app.stt.desktop` — and
      their running window carries that class
- [x] (discovered, review 2026-10-29) The macOS DMG's app carries the app's mark as its icon
- [x] (must not break) The three packaged desktop e2e paths stay green; item 70's window keeps the
      fonts, language marking and entries ([the requirement](../../roadmap/70-rust-gui-window.md))

## Acceptance criteria

- The bundle's fonts cover every accepted language, Japanese uses JP forms, and every desktop form
  has the app icon and one desktop identity.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (bundle fonts, Japanese SC, `.deb`
  icon/`desktopName`); not yet discussed.
- 2026-10-28: consultation with the user: per-script Noto fallbacks, detected language stored with
  the requested language as fallback, the favicon mark as the one icon, `.desktop` entries shared by
  all Linux forms. Test list approved as written; status set to spec-approved.
- 2026-10-29 (workspace): all 12 test-list items green (9 planned, 3 discovered and user-confirmed
  along the way: whisper-cli's `result.language` parsing, the `auto` placeholder's missing `lang`,
  whisper's `zh`/`yue` Han faces; plus the bundle's removal flag and the quoted `Exec`). Three
  packaged e2e paths verified on this branch: `.deb` 13 passed, Nix package 11 passed, Alpine bundle
  11 passed (skips are the other package kinds, the GPU-only specs and the MSI), and `nix flake
  check`'s new `font-coverage`, `font-forms` and `desktop-entries` checks are green. The list's
  item-70 clause could not be verified here — item 70 is spec-approved on `main`, not implemented;
  its spec's Decided section now states the requirement (the bundle's fonts and fontconfig, the
  transcript's `lang` marking, and the shared desktop identity: entry name, `StartupWMClass`, icon),
  as this item's Proposal step 4 asks. Status set to in-review; the diff awaits the user's approval.
- 2026-10-30 (workspace, the review's fixes): all six findings addressed. **One identity**, not two:
  `nix/desktop-identity.nix` derives the entry's file name and `StartupWMClass` from package.json's
  `desktopName`, the Nix package and the bundle install `app.stt.desktop.desktop`, and
  `16-desktop-entry.spec.ts` proves the entry *and* the live window's `WM_CLASS` for all three
  packages. **macOS icon**: `mac.icon: icon.png` is now explicit; the check (`desktop/check-mac-icon.ts`,
  wired into the `macos-dmg` job) verifies CFBundleIconFile, a real ICNS with a 512 px image, and
  rejects the framework's default name. Measurement on CI changed the premise: electron-builder's
  icon resolution falls back to a conventional `icon.png` in the project directory, so the DMG
  already carried the mark — the check is a guard, not a red-then-green, and two earlier reds were
  bugs in the check itself (a lookup that cannot exist on CI, then a vacuous name test), both fixed.
  **Item 70** now records the font/fontconfig, `lang` and identity requirement; **the backend
  reference** documents `detected_language` and the `language` exposure; **V14** drops the wrong ADR
  citation and ends with a newline; the `xprop` devenv addition was confirmed by the user's shell
  reload. Ran here: `gradle check`; frontend 268 tests (one worker after worker-contention flakes);
  docs `tsc`+prettier+checks; e2e `tsc`+prettier; `actionlint`. Ran on the user's CI at `9a2a0bb6`:
  Linux packaged `.deb` e2e, flake package e2e and bundle e2e all green, macOS DMG green (icon check
  and packaged e2e), Nix flake check/build green, Windows packaged zip green. The Windows *web* e2e
  (`Windows (e2e)`, item 79's job) failed three times on this branch while passing on `main`; its
  three failures are transcription/status waits outside this item's test list — a re-run was
  requested to separate load flake from defect. `macos-dmg` and the packaged suites were not run by
  the agent (no macOS, no Docker daemon here). Status set to in-review; the diff awaits the user.
- 2026-10-30 (Windows e2e, evidence after the re-run): on the same tip `9a2a0bb6` that run's Windows
  job failed 7 specs — `04-reorder`, `06-playback`, `09-merge`, `10-cancel-retry`, `11-delete`,
  `12-history`, `13-restart` — a different set from the 3 that failed on `ac8be13a`, every one a
  queue/transcription/order wait whose 240 s or 20 s timeout expired (the reorder predicate still
  returned `[0, 1, -1]`). None exercises this item's code: Windows has no desktop entry, and the
  `language` JSON is not asserted there; `04-reorder` and `13-restart` are order assertions a
  nullable column cannot affect. `main`'s identical job passes. Judged that job's own load
  flakiness, not this branch; filed in `docs/known-issues.md` (Tests: the `Windows (e2e)` job) rather
  than fixed here.
- 2026-10-30 (Windows e2e, resolved): re-running the failed job on the same tree (`40980209753`)
  passed — `51 passed, 3 skipped` — with no code change, and the earlier failures' set varied between
  runs. That job's load flakiness is confirmed; nothing in this item was changed for it. All three
  packaged desktop paths and the macOS DMG remain green.
- 2026-10-30 (final tip's Windows job, a different failure): run `40984522606` on `9de7eca7` failed
  before Playwright, in the e2e package's own `node --test`: `a backend that exits before answering
  health fails the start at once` (16 of 17 passed) rejected with the right `exited with code 3`, but
  the start took 5136 ms against that test's 5 s wall-clock bound (`e2e/lib/backend.test.ts:100`).
  The guarded behaviour held; filed as its own `docs/known-issues.md` row. Every path this item owns
  is green on that tip: Linux `.deb`, flake-package and bundle e2e, macOS DMG and packaged e2e,
  Windows packaged zip, Nix flake check/build, docs, gradle check and integrationTest, frontend.
- 2026-10-30 (macOS e2e on the final tip): one spec failed, `17-settings`, which timed out 300 s
  after `selectOption('fixed')` and `fill('3')` lost `#speaker-count`; a pre-existing settings-form
  race (a pending write's answer reset the mode), reproduced in a jsdom probe and filed in
  `docs/known-issues.md`. This branch changes no settings code. The other 54 specs passed, and the
  macOS DMG, packaged e2e, Nix and integration jobs are green.
- 2026-10-30 (root agent, rebased onto `main` after item 71's merge, the user's decision): item 71
  took migration V14 (`V14__task_queue_indexes.sql`), so this item's detected-language migration is
  now `V15__task_detected_language.sql` and `DatabaseSpec` expects V1 to V15. The consolidated DDL
  (`docs/backend-schema.sql`) gains `detected_language` as the last `tasks` column, as
  `ALTER TABLE` adds it. The `Windows (e2e)` backlog row now states that #80 took the job out of the
  required aggregate; the flake itself stays open there.
