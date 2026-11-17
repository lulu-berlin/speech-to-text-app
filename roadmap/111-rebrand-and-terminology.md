# 111 — Rebrand to `protocolulu`, rename the screens to Recordings and Transcripts, and audit the copy

Status: draft
Depends on: [88](../docs/specs/localization.md) (the catalogs, glossaries, review records and
catalog checks this renames), [89](89-service-package.md) (package and artifact names),
[66](../docs/specs/local-service-and-frontends.md) (the per-user directories, the app ID and the
launch model)
Blocks: [108](108-rust-backend-switch.md) — the switch adopts the new names rather than renaming
everything a second time

## Goal

The product is called **`protocolulu`** everywhere a person or an installer sees it, and the app's
own vocabulary says **Recordings** for the queue screen and **Transcripts** for the history screen, in
all 28 shipped languages. The English copy is audited in one pass — labels, errors, empty states,
tone — and the result is a terminology table every locale follows. Internal identifiers are renamed
too, with a migration for existing installs: binaries, crates, environment variables, the desktop
identity and the per-user data directory.

## Evidence

- **The name is a literal in several places, not one constant**: `frontend/index.html:7` (the
  document title), `frontend/src/app/AppHeader.tsx:174,176` (the home link's name and the brand), the
  never-translated list in the catalog check (`Speech-to-Text`), the package descriptions
  (`flake.nix`), README and `docs/architecture.md`, and the window/tray/DMG/MSI titles.
- **The screen words are catalog entries**: after item 88, the navigation, headings and every
  sentence that says "queue" or "history" live in `frontend/src/locales/*.json`, with a glossary and
  per-string review record per locale; the English catalog is the source the other 27 follow.
- **The routes are paths**: `frontend/src/app/App.tsx:67-71` serves `/queue`, `/history` and
  `/history/:taskId`; the e2e helpers and many specs navigate by the visible names.
- **The identifiers carry the old name**: crates `stt-client`, `stt-window`, `stt-ui`; binaries
  `stt-app`, `stt-service`, `stt-tray`; the runner path the client resolves beside it
  (`client/stt-client/src/lib.rs`, `start_runner_beside`); environment variables `STT_DATA_DIR`,
  `STT_HOST`, `STT_PORT`, `STT_CONFIG_DIR`, `STT_RUNTIME_DIR`, `STT_STATIC_DIR`, `STT_SPEAKER_MODEL`,
  `STT_WHISPER_MODEL` (`backend/src/main/kotlin/stt/config/Config.kt`, `devenv.nix`); the per-user
  directories and their environment overrides (`docs/service-dirs.json`); the Linux desktop identity
  (`nix/desktop-identity.nix`, `app.stt.desktop` per item 70); the database file `stt.db`; the Nix
  attributes and flake outputs.
- **The repository name is a slug in 18 files**: the README's four clone blocks (including
  `cd speech-to-text-app`), `desktop/package.json:11`, and — the ones that matter — the pinned
  release-asset URLs for the repo's own artifacts in `versions.json`, `nix/desktop-artifacts.nix`,
  `nix/models.nix`, `nix/versions.nix` and the generator template `nix/release-asset.nix:42`. The
  historical specs' CI-run links are evidence of past runs and keep the old slug (GitHub redirects
  them).
- **Item 66 promised existing installs keep working**: its service directories, the autostart entry
  (`client/stt-client/src/autostart.rs`) and the stored data are the app's durable state. The user
  supersedes that promise here (2026-11-15): the app is not stable and has no user base, so this item
  makes a hard break instead of carrying a migration.

## Decided (consultation, 2026-11-15)

- **The name** (the user): the app is `protocolulu`, lowercase, never translated, in every UI, window,
  tray, installer, package description, README and new documentation. Historical specs keep the name
  they were written with.
- **The screens** (the user): the queue screen is **Recordings**; the history screen is
  **Transcripts**. Their navigation labels, headings, empty states and every sentence that names them
  follow, so no copy contradicts the new names.
- **Everything internal is renamed too, as a hard break** (the user, 2026-11-15): binaries, crates,
  environment variables, the app ID, the per-user data directory and the database file (now
  `protocolulu.db`). The app is not stable and has no user base, so there is no migration and no
  backwards compatibility: the old names are removed outright, old data directories are neither read
  nor moved, and old environment variables are not consulted. The old desktop entry and app ID are
  removed, not kept beside the new ones.
- **All 28 locales** (the user): each language gets its own words for recordings and transcripts,
  recorded in its glossary and review record, with the regenerated review pages and the catalog
  checks green.
- **The routes change** (the user): `/recordings`, `/transcripts` and `/transcripts/:taskId`, with the
  old `/queue`, `/history` and `/history/:taskId` redirecting (a deep link to a task lands on its
  transcript).
- **A full English copy audit** (the user): labels, errors, empty states and tone are reviewed in one
  pass and recorded as a terminology table; the other locales follow it, as item 88's Translation
  standard requires.
- **The GitHub repository is renamed too** (the user): `lulu-berlin/protocolulu` replaces the old
  slug. The rename itself is the user's action on GitHub; this item updates every in-repo reference:
  clone instructions and the directory they enter, package metadata, the release-asset URLs this
  repo pins for its own artifacts, and the release-asset generator. Historical specs and recorded
  run links keep the old slug.
- **Before the Rust switch** (the user): this item lands before item 108, so the switch adopts the
  new names, directories and database file once. Item 108's `Depends on` gains this item; the Rust
  code that exists when this item is worked (item 107's service included) is renamed with everything
  else; if item 107 lands after this item, it uses the names set here, and item 108 keeps the switch
  consistent either way.

## Proposal

1. **Name and identity**: one product-name source for the UI and docs; `protocolulu` in the document
   title, the header brand and its accessible name, the window, tray, DMG/MSI names and descriptions,
   README and `docs/architecture.md`; the never-translated list follows.
2. **Terminology and copy audit**: an English terminology table (recordings, transcripts, task,
   recording, transcript, speaker, turn, merge, upload, settings, status words, errors) with the
   tone rules; the English catalog is rewritten to it; every locale's catalog, glossary, style guide
   and review record carry their own words, and `docs/generate-reviews.ts` regenerates the pages.
3. **Routes**: `/recordings` is the queue screen, `/transcripts` the history list and
   `/transcripts/:taskId` a transcript; the old paths redirect, and the e2e helpers/specs navigate
   the new ones.
4. **Identifiers**: crates, binaries, Nix attributes/outputs, environment variables, the runner path
   the client resolves, the app ID and the desktop entry (`app.protocolulu.desktop`), the per-user
   config/runtime/data directories and the database file (`protocolulu.db`).
5. **Hard break, no migration**: the old names are removed, not aliased. Nothing reads an old
   environment variable, nothing reads or moves the old data/config/runtime directories (a start that
   finds the old data directory logs a warning naming it and uses only the new paths), and an old
   database file is not opened. The installer removes the old desktop entry and writes the new one;
   an old autostart entry is not honored and the new version writes its own.
6. **Packaging and CI**: the artifacts, their descriptions and the CI jobs carry the new names; the
   packaged e2e installs the new package and runs the journey; the autostart, launch and data-dir
   specs assert the new paths.
7. **Repository references**: README clone blocks and their `cd` directory, package metadata, every
   URL this repo pins for its own release assets (`versions.json`, `nix/desktop-artifacts.nix`,
   `nix/models.nix`, `nix/versions.nix`) and the release-asset generator template
   (`nix/release-asset.nix`) use the new slug; the pins' sha256 values do not change.

## Open questions

- **Per-language words**: the exact word for recordings and transcripts in each locale is settled per
  locale through item 88's review process; a language with no good counterpart records that choice.
- **Release assets under the new repository**: are the existing tags' assets re-uploaded under the
  new name, or do the updated pins rely on GitHub's redirect for renamed repositories? The pins
  change either way; the answer decides whether old tags must be re-created.

## Test list

Proposed (approve with the spec; behaviors only):

- [ ] The UI, document title, window, tray, package descriptions and README say `protocolulu`, and
      the old name appears in no user-visible string, title or description
- [ ] Every shipped locale names the Recordings and Transcripts screens in its navigation and
      headings, and no catalog keeps the old screen words in a user-visible string
- [ ] The English copy follows the terminology table: recordings/transcripts, the status words, the
      empty states and the errors; every locale's glossary, review record and generated page carry its
      own terms, and the catalog checks are green
- [ ] `/recordings`, `/transcripts` and `/transcripts/{id}` serve their screens; `/queue`,
      `/history` and `/history/{id}` redirect to them, and a task deep link opens its transcript
- [ ] A hard break: with the old data directory present, the service starts fresh on the new paths,
      reads nothing from the old directory, logs a warning naming it, and the old database file is not
      opened
- [ ] The new environment variables configure the service (data dir, config, runtime, port, models,
      static dir); the old names are not consulted, and a script that sets only the old data-dir name
      does not start the service
- [ ] An upgrade over an old install removes the old desktop entry and writes the new one; an old
      autostart entry is not honored, and the new version's entry names the new runner path and starts
      one service
- [ ] No current in-repo reference names the old repository: the README clone blocks and the
      directory they enter, package metadata, every pinned release-asset URL and the generator use the
      new slug, and the pins still fetch; historical run links are unchanged
- [ ] The installed binaries and packages are named `protocolulu`; `nix run`, the `.deb`, the DMG, the
      MSI and the bundles launch the app under the new name
- [ ] The desktop entry uses the new app ID and icon name, and a fresh install shows one entry
- [ ] (e2e, ADR 0046) The packaged journey runs under the new names: upload → recording → transcript,
      navigation through Recordings and Transcripts, and the settings and speaker paths unchanged
- [ ] (must not break) The API contract, the transcript content and formats, Docker, the dev server and
      the item-88 localization behavior stay as they are; historical specs keep their wording

## e2e stance

Item 88's journeys and the packaged desktop journey are renamed rather than replaced; one packaged
journey asserts the new names, routes and paths end to end (ADR 0046).

## Approval log

- 2026-11-15: filed from the user's consultation: the app is `protocolulu` (everything internal is
  renamed, with a migration), the queue screen is Recordings, the history screen is Transcripts, all
  28 locales, new routes with redirects, and a full English copy audit recorded as a terminology
  table. Test list proposed; open questions above need answers before `spec-approved`.
- 2026-11-15 (second consultation): the user settled every open question but the per-language words.
  The item lands before the Rust switch (108 depends on it); the old environment variables are
  removed, never read; there is **no migration and no backwards compatibility** — the app is not
  stable and has no user base, so the old data/config/runtime directories are ignored and a warning
  names them; the database is `protocolulu.db`; the old desktop entry is removed and the app ID is
  renamed (`app.protocolulu.desktop`). The Rust code that exists when the item is worked is renamed
  with everything else. Test list updated to the hard break.
- 2026-11-15 (third decision): the GitHub repository is renamed as well (`lulu-berlin/protocolulu`,
  the user's action on GitHub). The item updates every in-repo reference — the README clone blocks and
  their `cd` directory, package metadata, the release-asset URL pins and the generator — while
  historical specs and recorded run links keep the old slug. Whether old tags' assets are re-uploaded
  or rely on GitHub's redirect is the remaining open question.
