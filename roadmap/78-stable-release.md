# 78 — The first stable release: release gates, one version everywhere, and the release process

Status: spec-approved
Depends on: [49](../docs/specs/windows-support.md), [52](../docs/specs/edit-transcription.md),
[65](../docs/specs/row-color-phase-observation.md), [68](../docs/specs/windows-audio-only-ffmpeg.md),
[69](../docs/specs/macos-audio-only-ffmpeg.md), [72](../docs/specs/frontend-state-and-errors.md),
[74](../docs/specs/desktop-presentation.md), [76](76-right-to-left-transcription.md),
[77](../docs/specs/test-harness-temp-dirs.md), [79](../docs/specs/windows-e2e-journey.md),
[84](84-e2e-reliability.md), [87](87-writes-wait-for-the-connection.md) — the gates
proposed below — and the six MED known issues listed under Evidence, which no roadmap item handles
today
Blocks: —

## Goal

Ship **1.0.0**: every release-blocking item and known issue is closed (or its risk is explicitly
accepted and written down), the app carries **one version** everywhere it appears (backend, flake
packages, frontend/desktop/e2e metadata, packaged artifact names, a version surface the app
reports), and a release is produced by a **documented, repeatable process** — version bump, tag,
release workflow, checksums, published artifacts and notes — instead of by hand.

## Evidence

### The version is duplicated and nothing reports it

- `backend/build.gradle.kts:8` `version = "0.1.0-SNAPSHOT"`; `flake.nix:179` (backend
  `0.1.0-SNAPSHOT`), `:243` (frontend `0.1.0`), `:339` (app `0.1.0`); `frontend/package.json:4`,
  `desktop/package.json:6`, `e2e/package.json:4` all `0.1.0`.
- No surface reports the version: `GET /api/health` answers `{"status":"ok"}`
  (`backend/src/main/kotlin/stt/http/HealthRoutes.kt:8-10`); the packaged artifact names carry the
  version instead (e.g. `Speech-to-Text-0.1.0-win-x64.zip` in item 49's CI runs).
- `checkVersions` (`build.gradle.kts` root) guards Gradle, the nixpkgs rev and the pinned tools — not
  the app version, and `versions-sync` guards `versions.json` against `nix/versions.nix`.

### There is no release process

- No app-version tag (`git tag` holds only `item48-final-tree`), no `CHANGELOG.md`, no release
  workflow (`.github/workflows/` holds `ci.yml` and `codeql.yml`). The existing GitHub Releases are
  tool and model assets (`desktop-ffmpeg-v2`, `desktop-whisper-cpp-v1`, `desktop-mesa-v1`,
  `desktop-models-v1`), uploaded by the user; agents never publish (ADR 0050).
- Packaging decisions a release must revisit: **unsigned v1, no auto-update**
  (`docs/specs/electron-desktop-app.md:47-49`; `desktop/electron-builder.yml:88` `identity: null`),
  and an unsigned per-user MSI for Windows (item 49's decision).
- Delivery matrix today: Linux x86-64 (Nix package, `.deb`, single-file bundle, Docker built
  locally from the flake — no registry), macOS Apple Silicon (Nix package, DMG), Windows x64 (item
  49, in progress: portable zip and per-user MSI). Intel macOS is unsupported (README).
- Data: a forward-only Flyway chain `V1`–`V11` (10 migrations, `backend/src/main/resources/db/migration`);
  nothing tests that a data directory written by an older release still opens.

### Open roadmap items

| Item | Status | Release gate? (proposed) | Reason |
| --- | --- | --- | --- |
| 49 Windows support | in progress | **yes** | 1.0 claims Windows as a supported platform |
| 52 Edit transcription | in progress | **yes** | the copy-edit workflow is core to a finished transcript |
| 54 TypeScript for the remaining scripts | done | after 1.0 | hygiene; no behavior change |
| 65 `59-row-color` chunking observation flake | done | **yes** | a flaky journey weakens release verification |
| 66 Local service and frontends | spec-approved | after 1.0 | architecture change; new frontends |
| 67 TUI frontend | spec-approved | after 1.0 | new frontend |
| 68 Windows audio-only ffmpeg | done | **yes** | otherwise the Windows deliverable ships bytedeco's full GPL `ffmpeg`+`ffprobe`, unlike Linux/macOS; licensing and size |
| 69 macOS audio-only ffmpeg | in progress | **yes** | nearly done; darwin artifact consistency |
| 70 Rust GUI window | spec-approved | after 1.0 | replaces Electron; a new shell |
| 71 Repository and chunking performance | done | after 1.0 | scaling improvement, not a correctness defect |
| 72 Frontend state and errors | draft | **yes** | a stable UI must not hide failed actions |
| 73 Split a speaker | spec-approved | after 1.0 | the manual remedy for wrongly merged speakers |
| 74 Desktop presentation | done | **yes** | icon, `.desktop` entry and glyph coverage are release polish, not optional |
| 75 Retire the Python oracle | done | after 1.0 | docs-only |
| 76 Right-to-left transcription | draft | **yes** | correctness for scripts the app already accepts |
| 77 Upload spec shared temp dir | in progress | **yes** | a `macOS (gradle check)` flake undermines release verification |
| 79 Full e2e journey on Windows | done | **yes** | 1.0 claims Windows support; the journey is the only suite not run there (item 49 covers the packaged specs only) |
| 83 Diarization accuracy | spec-approved | after 1.0 | detection quality, measured on the user's recordings (decided 2026-10-30) |
| 84 e2e reliability | spec-approved | **yes** | the Windows journey must gate again and the suites must not depend on clocks, sleeps or a stale UI (decided 2026-10-30) |
| 85 CI wall time | spec-approved | after 1.0 | CI speed, not release quality; item 84 covers reliability (decided 2026-10-31) |
| 86 Mobile feasibility | spec-approved | after 1.0 | research into an Android/iOS port; the 1.0 support matrix is desktop only (decided 2026-10-31) |
| 87 Writes wait for the connection | spec-approved | **yes** | a live write control while the app cannot write, and a browser's error text on screen, are release quality issues (decided 2026-10-31) |

### Known issues a stable release must close (MED; planned by this item)

These leave `docs/known-issues.md` when this item is approved; the LOW rows stay in that backlog and
are not gates.

| Issue | Evidence |
| --- | --- |
| Upload acceptance is the client-supplied extension only (any bytes renamed `x.mp3` pass `201` and fail at decode) | `backend/src/main/kotlin/stt/http/UploadRequest.kt:44-58` |
| The stored `originalFilename` is embedded raw into `Content-Disposition` (a `"`/CR/LF breaks or injects header lines) | `outputs/DownloadFileName.kt:22-29`; `http/TranscriptRoutes.kt:82` |
| Wall-clock/flaky test patterns that pass vacuously on a slow machine | `engine/ProcessingEngineQueuePauseSpec.kt:35`; `engine/ProcessingEngineCancelSpec.kt:78,112`; `diarization/SpeakerIdsSpec.kt:224`; `diarization/EmbeddingsSpec.kt:97-98`; `http/EventsDisconnectSpec.kt:47`; `process/ProcessRunnerSpec.kt:376` |
| The packaged-desktop quit spec's `finally` throws after `await exited`, failing a passing spec and masking the outcome (fixed by [item 82](../docs/specs/ci-diagnosability.md), 2026-10-25) | `e2e/desktop-tests/13-second-quit-during-shutdown.spec.ts:33` (`:29`) |
| JDK 21 hardcoded in four places in `backend/build.gradle.kts` while `versions.json` holds the pin; `checkVersions` does not cover it | `backend/build.gradle.kts`; `versions.json` |
| Docker build cache busted by non-input files (`COPY . .`) | `Dockerfile`; `.dockerignore` |

Two more MED rows land with gates: the `06-playback` paused-time race (recorded on branch 49) and
the e2e ffmpeg WAV-muxer issue (recorded on branch 52). Both must be closed before the release.

## Proposal

1. **Close the gates.** Implement each gate item and each MED issue above, or record an accepted
   risk with its rationale in this item (and, if it stays open, in `docs/known-issues.md`).
2. **One version.** A root `VERSION` file is the authority (SemVer `MAJOR.MINOR.PATCH`; `1.0.0`
   first, `X.Y.Z-rc.N` for pre-releases). A `checkAppVersion` task (next to `checkVersions`) fails,
   naming the file, when the backend `version`, the flake packages' versions, `frontend/`,
   `desktop/` and `e2e/` `package.json` versions or the packaged artifact names disagree; CI runs
   it. Dev builds carry the version with a `-SNAPSHOT`/dev suffix, release builds the bare one.
3. **A version surface.** `GET /api/health` gains `version` (docs/api example and contract test),
   the UI shows it (footer/About, decided in consultation) and the backend logs it at startup.
4. **The release process.** `docs/release-process.md` plus a `release.yml` workflow:
   - a version-bump PR (`VERSION`, the package metadata, a `CHANGELOG.md` entry), merged through
     the normal PR flow;
   - tag `vX.Y.Z` on the merged commit; the workflow is dispatched for that tag;
   - it builds each platform's artifact on its own runner (Linux `.deb` + bundle + flake package,
     macOS DMG, Windows MSIX + zip + MSI), signs them (point 5), runs the packaged specs against the
     built artifacts, writes `sha256` files and uploads a **draft** GitHub Release with the notes
     from `CHANGELOG.md` and the supported-platform matrix; the user reviews and publishes, and
     uploads the MSIX to the Store separately;
   - a tag whose version does not equal `v` + `VERSION`, or whose version has no changelog section,
     fails the workflow.
5. **Distribution and signing** (decided 2026-10-23, supersedes item 50's "unsigned v1" —
   [ADR 0060](../docs/decisions/0060-windows-store-and-unsigned-macos.md)): on Windows the
   recommended artifact is an **MSIX published through the Microsoft Store** (Microsoft signs it on
   publish: no developer certificate, no subscription; one-time Store registration), which adds the
   MSIX target, the Partner Center submission and the Store review step to the release. The
   portable zip and per-user MSI stay unsigned direct downloads, documented as such. The package
   declares `unvirtualizedResources` so Store, zip and MSI builds share `%APPDATA%\stt-app`; if the
   Store rejects it, the limitation is documented and tested. The **macOS DMG stays unsigned**:
   Developer ID and notarization need an annual Apple Developer Program membership (US$99, no
   open-source waiver), so the README and the release notes document the first-launch bypass
   (right-click → Open, or System Settings → Privacy & Security → Allow Anyway); the Nix path never
   prompts because Nix-built files carry no quarantine attribute. There are no signing secrets; the
   Store submission and every other publish stay the user's manual step.
6. **Upgrade and rollback.** A fixture data directory written by the previous release opens in the
   new one with its tasks, transcripts and settings intact (Flyway). The notes state that downgrades
   are not supported; rollback is a patch release — a version is never reused.
7. **Release notes and support matrix.** `CHANGELOG.md` (Keep a Changelog headings) and, per release,
   the matrix (Linux x86-64, macOS Apple Silicon, Windows x64; Intel macOS unsupported). With point
   5 signed, no Gatekeeper/SmartScreen bypass belongs in the notes.

## Decided (consultation, 2026-10-23)

- **Gates**: the in/after split proposed above is approved as written: in 1.0 are 49, 52, 65, 68,
  69, 72, 74, 76, 77, 79 and the six MED known issues; after 1.0 are 54, 66, 67, 70, 71, 73, 75.
- **Signing**: Windows ships through the **Microsoft Store** as an MSIX (Microsoft signs it; the zip
  and MSI stay unsigned direct downloads), per ADR 0060. No subscription; the one-time Store
  registration fee is accepted. The **macOS DMG stays unsigned** — Developer ID and notarization
  need an annual Apple Developer Program membership, and Apple's fee waiver does not cover
  open-source projects or individuals — with the Gatekeeper bypass documented.
- **Auto-update**: none in 1.0. A newer release is announced through the release page only.
- **Versioning**: SemVer `1.0.0` first, `X.Y.Z-rc.N` pre-releases. The `/api` contract may change in
  1.x; a frozen API starts at 2.0.
- **Docker**: the image stays a local build from the flake; nothing is pushed to a registry.

## Test list (approved 2026-10-23; behaviors only)

- [ ] (version) A version-drift check fails, naming the file, when any consumer differs from
      `VERSION`: the backend's Gradle version, the flake packages' versions, the three
      `package.json` versions or the packaged artifact names
- [ ] (version) `GET /api/health` reports the version from `VERSION`, shaped by a `docs/api` example;
      a packaged app reports the same version it was built with
- [ ] (release) The release workflow refuses a tag whose name is not `v` + `VERSION`, and one whose
      version has no `CHANGELOG.md` section, naming the mismatch
- [ ] (release) A release build names every artifact with its version and uploads a `sha256` for each
- [ ] (release) The macOS DMG is unsigned, and the README and the release notes carry the exact
      first-launch bypass steps; a docs check fails when they are missing
- [ ] (release, Windows) The Store MSIX installs on a clean machine without a developer certificate
      and without a SmartScreen block, and the zip/MSI downloads are documented as unsigned
- [ ] (upgrade, Windows) A Store MSIX update keeps the app's data, and with `unvirtualizedResources`
      a data directory written by the zip or MSI build opens in the Store build with its history
      intact
- [ ] (release) The release process document names every artifact and the workflow job that produces
      it; a docs check fails when a release workflow job is not documented
- [ ] (upgrade) A data directory written by the previous release opens in the new one with its
      tasks, transcripts and settings intact
- [ ] (e2e, ADR 0046) The packaged Linux artifact from a release build serves the UI and reports the
      version it was built with
- [ ] (must not break) The existing packaging jobs (`.deb`, DMG, zip, MSI) and their packaged specs
      stay green, and the tool/model asset pins are unchanged

## Acceptance criteria

- 1.0.0 exists on the release page, built from a tag, with every artifact carrying the version,
  a `sha256` beside it, notes and the support matrix; the published artifacts are the ones CI built
  and smoke-tested.
- One `VERSION` governs every place the app's version appears, and CI fails on drift.
- Every gate item and MED issue above is closed, or its accepted risk is recorded here.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed as a draft by the root agent at the user's request, after reading every open
  roadmap item, the known-issues backlog and the current version/packaging surfaces. Not yet
  discussed.
- 2026-10-23 (consultation, the user): gates as proposed; macOS notarized **and** MSI signed
  ([ADR 0060](../docs/decisions/0060-windows-store-and-unsigned-macos.md), item 50's spec points at
  it); no auto-update; SemVer `1.0.0` with the `/api` contract free to change in 1.x; Docker stays a
  local build. Test list extended with the signing scenarios (10 items), then approved as worded.
  The six MED rows left `docs/known-issues.md` in the same commit. Status: spec-approved.
- 2026-10-23 (consultation follow-up, the user): Windows ships the **MSIX through the Microsoft
  Store** as the recommended artifact; the zip and MSI remain unsigned direct downloads. The
  one-time Store registration fee is accepted; no subscription signing. The user's condition is
  that **upgrades keep the data from the previous version** — covered by the item's upgrade
  scenarios (same package family across updates; `unvirtualizedResources` so the Store build shares
  `%APPDATA%\stt-app` with the zip/MSI builds; a documented limitation if the capability is
  refused). ADR 0060 and the signing scenarios were updated accordingly.
- 2026-10-23 (consultation follow-up, the user): macOS stays **unsigned**. A Developer ID and
  notarization require an annual Apple Developer Program membership (US$99), and Apple's fee waiver
  covers only nonprofits, accredited educational institutions and government entities — it needs a
  legal entity (D-U-N-S, organization domain) and is not open to individuals or to open-source
  projects as such. The README and the release notes document the Gatekeeper bypass; the Nix path
  never prompts (no quarantine attribute). There are no signing secrets. ADR 0060 retitled and
  rewritten; the notarization scenarios replaced by the bypass-documentation check. Test list: 11
  items.
- 2026-10-23 (the user): roadmap item 79 — the full e2e journey on Windows — is added as a 1.0 gate
  and to this item's dependencies, since 1.0 claims Windows support and the journey is the only
  suite not run there.
- 2026-10-30 (the user): roadmap item 84 — e2e reliability (the Windows journey back in the
  aggregate, no wall-clock or sleep-based assertions, a journey against the current UI, the settings
  draft race) — is added as a 1.0 gate and to this item's dependencies.
- 2026-10-31 (the user): roadmap item 87 — writes wait for the connection, a message for a failed
  write in the app's words, and "Reset name" disabled when there is nothing to reset — is added as a
  1.0 gate and to this item's dependencies.
