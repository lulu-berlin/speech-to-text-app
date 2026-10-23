# 74 — Desktop presentation: font coverage for every language, Japanese glyphs, and an app icon with a desktop entry

Status: draft
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
- **No app icon or desktop identity**: `desktop/electron-builder.yml` declares no `icon` and
  `desktop/package.json` no `desktopName`; the user's 2026-10-15 `pnpm --dir desktop dist` warned
  about Electron's default icon and that desktop environments may not link the window to the menu
  entry. The Nix package and the bundle have no `.desktop` entry at all.
- Item 70 replaces the Electron shell with Slint and rewrites the packaging; the icon, the desktop
  entries and the font set must be carried into that packaging (a requirement on its implementation,
  not a dependency, so this item can land first and its test list guards the result).

## Proposal

1. Choose a fallback font strategy by measurement: add per-script fallback families (e.g. Noto for
   the missing scripts and the two extra Latin letters) or switch the UI family to one with wider
   coverage; record the closure/payload delta for the bundle as item 50 measured fonts.
2. Mark the transcript's language on the rendered transcript (task language/detected language) so
   fontconfig picks the JP/TC/Hans face; keep the app shell's own `lang="en"`.
3. Add the app icon (the mascot still) and `desktopName` + `linux.syncDesktopName` to the packages,
   and `.desktop` entries for the Nix package and the bundle; verify one window/menu identity.
4. Note in item 70's implementation that the Slint window must keep the fonts, language marking and
   desktop entries.

## Open questions

- The font set and its size budget (per-script Noto subsets vs a single wide-coverage family);
  which scripts share a family.
- The language source for the `lang` attribute: the task's requested language, whisper's detected
  language, or both (fallback).
- The icon artwork and sizes (the mascot still vs the favicon; `.icns`/`.ico`/PNG set) and whether
  the Nix package/bundle entries share the `.deb`'s desktop file.

## Test list (proposed; to be approved at the consultation)

- [ ] Every language in `docs/api/languages.json` renders its sample text in the bundle with no
      missing glyphs (a font-coverage check over the shipped font set)
- [ ] A `ja` transcript renders Japanese glyph forms; `zh-Hans` and `zh-Hant` stay distinct
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
