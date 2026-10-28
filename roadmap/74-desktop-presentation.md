# 74 — Desktop presentation: font coverage for every language, Japanese glyphs, and an app icon with a desktop entry

Status: spec-approved
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

## Test list (approved; behaviors only)

- [ ] Every language in `docs/api/languages.json` renders its sample text in the bundle with no
      missing glyphs (a font-coverage check over the shipped font set)
- [ ] A `ja` transcript renders Japanese glyph forms; `zh-Hans` and `zh-Hant` stay distinct
- [ ] An `auto`-uploaded task stores whisper's detected language, the API exposes it, and its
      transcript marks that language; a task whose detection is empty falls back to the requested
      language
- [ ] The `.deb` installs a menu entry with the app icon and `desktopName`, and the running window
      is associated with it (one app identity)
- [ ] The Nix package and the bundle carry matching `.desktop` entries and icon
- [ ] (must not break) The three packaged desktop e2e paths stay green; item 70's window keeps the
      fonts, language marking and entries

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
