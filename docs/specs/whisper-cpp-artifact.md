# 63 — Stop compiling whisper-cpp on every uncached machine

Status: done (roadmap item 63, approved 2026-10-20)
Depends on: —
Blocks: —

## Goal

The flake's whisper-cpp (Vulkan, no SDL, pinned to the release the Gradle bootstrap's prebuilt
binary uses) is no longer compiled on every uncached machine and CI run, without changing which
whisper-cli version the Nix build, the devenv and the Gradle path run.

## Evidence

- `nix/whisper-cpp.nix` overrides nixpkgs' `whisper-cpp` with `vulkanSupport = true`,
  `withSDL = false` and the source of tag `v1.8.4` (`nix/versions.nix` `wcTag`), so its output is
  not a nixpkgs build and not in cache.nixos.org. It feeds `nix build .` (the server package and
  so the Docker image), `.#desktop`, `.#desktop-bundle` and the devenv.
- PR #9's run `38825990732` spent about eight minutes building it for `.#desktop`.
- nixpkgs `494ce7fd` (the flake's pin) has `whisper-cpp-vulkan` (`pkgs/top-level/all-packages.nix:5306`),
  but at version 1.9.2 (`pkgs/by-name/wh/whisper-cpp/package.nix:84`) and with `withSDL ? true`
  (`:32`) — the SDL example `nix/whisper-cpp.nix:15-19` cut (~330 MiB).
- The 1.8.4 pin exists so the Nix build and the Gradle bootstrap's jiang1997 1.8.4 prebuild run one
  version (item 33 consultation, 2026-10-11). jiang1997 publishes that tag for linux-x64,
  darwin-arm64 and darwin-x64 (`nix/versions.nix:30-43`).
- `nix/prebuilt-artifact.nix:1` names this item: the shared helper (items 56 and 62) is written to
  serve a third artifact.
- The Gradle path's `whisper-cli` runs the same 1.8.4 tag (Vulkan on Linux, CoreML on macOS), so an
  artifact of the Nix build keeps the three paths on one version.

## Decided (consultation, 2026-10-19)

- **A pinned prebuilt artifact through `nix/prebuilt-artifact.nix`** (the user): the same mechanism
  as the bundle's Mesa (item 56) and the audio-only ffmpeg (item 62) — a versioned GitHub Release
  asset of our own x86_64-linux build, pinned by url, sha256, the source build's out path and its
  direct references, fetched and re-linked, never built from source when a pin is registered.
- **Every system the flake declares ships its artifacts** (the user; amended at the root-agent
  review): at land time the flake declares only x86_64-linux, so this item pins that one; item 48
  (macOS) adds its system *and* its whisper-cpp artifact with it, and a flake check enforces it.
  Windows is outside the flake: its whisper-cli artifact is item 49's pinned
  `versions.nix`/`versions.json` entry, enforced by item 49's tests.
- **Release tag `desktop-whisper-cpp-v1`** (the user): a new build with another recipe or nixpkgs
  bump gets a new tag, as for Mesa and ffmpeg; the asset printer prints the pin, the user builds and
  uploads, agents never publish.
- **The 1.8.4 Vulkan, no-SDL, no-ffmpeg build stays** (the version decision of 2026-10-11 stands):
  moving to nixpkgs' 1.9.2 `whisper-cpp-vulkan` would change the version the Gradle path serves,
  and its SDL default brings the ~330 MiB chain back; overriding SDL off re-derives the package,
  so cache.nixos.org cannot have that variant — the compile would return.
- **e2e stance**: a packaging item that changes no app feature; the Linux journey, the packaged
  desktop specs and the whisper-cli smoke are its end-to-end proof (ADR 0046 exemption).

## Proposal

1. Add `whisperCpp.x86_64-linux` to `nix/desktop-artifacts.nix` and route `nix/whisper-cpp.nix`'s
   output through `nix/prebuilt-artifact.nix` (source = the current override, artifact = the pin);
   export `source` and `whisperCpp` and add `desktop-whisper-cpp-asset` to the flake.
2. The asset is one output (`whisper-cli` and its files), built from the same derivation name so the
   re-link can rewrite the embedded build path in place (items 56/62's constraint).
3. Verification: the asset-pin match is a producer app (`nix run .#whisper-cpp-asset-pin`), not a
   flake check; the missing/hash-mismatch/stale-pin/stray-path/reference-closure gates (as for Mesa
   and ffmpeg), the everywhere check, the declared-system policy check and the closure-slim/size
   measurements stay flake checks, so `nix flake check` builds no source.
4. Docs: README/devenv/comments describe the shell's and the package's whisper-cli as the pinned
   artifact, and the platform-artifact policy.
5. The user builds `.#desktop-whisper-cpp-asset` and uploads the tar to `desktop-whisper-cpp-v1`;
   the pin is pasted into `nix/desktop-artifacts.nix`.

## Open questions

- Which store paths a whisper-cpp build names at all (RUNPATH, a configure prefix); the scan and the
  pinned `references` list are read off the first build (items 56/62 settled theirs the same way).
- None for the user: candidate 1 (nixpkgs 1.9.2) and candidate 3 (accept) are rejected by the
  decisions above; the SDL-closure question is moot.

## Test list (approved 2026-10-19; behaviors only)

- [x] (basic) On x86_64-linux, the server package (`nix build .`, and so `.#docker-root`),
      `.#desktop`, `.#desktop-bundle` and the devenv shell ship the pinned prebuilt whisper-cli: on a
      store without it the dry-run lists no whisper-cpp source build (the asset fetch and the
      re-linked output only).
- [x] (basic) The shipped whisper-cli is the pinned 1.8.4 Vulkan build: `whisper-cli --help`
      succeeds, and it transcribes the committed FLAC fixture
      (`backend/src/test/resources/upload-formats/sample.flac`) to the committed expected text for
      the tiny model, derived once from the source build with its provenance recorded; the check
      runs the shipped artifact only.
- [x] (basic) The prebuilt whisper-cpp's closure contains no SDL/GTK/GStreamer chain and no nixpkgs
      ffmpeg.
- [x] (basic) `.#desktop-whisper-cpp-asset` prints a pin naming this build (store path, references)
      and the `desktop-whisper-cpp-v1` release URL; `nix run .#whisper-cpp-asset-pin`, a producer
      app under `apps.` (not a default `checks.` entry), builds the asset and asserts the pin
      matches it, so `nix flake check` never builds the source.
- [x] (edge) A registered asset that is missing or does not match its sha256 fails naming the asset,
      url and sha256; an asset made from another source build fails at evaluation naming both store
      paths; the stray-path scan fails on an unregistered path; every pinned reference is in the
      output's closure; no occurrence of the asset's build store path survives.
- [x] (policy) A flake check asserts every system the flake declares has a pinned whisper-cpp
      artifact — today `[x86_64-linux]`; item 48 adds macOS with its artifact. Windows is outside
      the flake: its whisper-cli artifact is item 49's pinned `versions.nix`/`versions.json` entry,
      enforced by item 49's tests.
- [x] (edge) With no artifact registered for a system, the helper still returns the source build
      (a direct import over a non-declared system), and no stale-pin error fires there.
- [x] (size) The bundle, the server package and docker-root closure sizes are measured and recorded
      against items 61/62's numbers; the bundle is at or below them, and any growth stops the item
      for the user's decision.
- [x] (goal, recorded) The CI Nix job that builds `.#desktop` compiles no whisper-cpp (its log holds
      no whisper-cpp build step; the saved build time is recorded).
- [x] (must not break) `nix flake check`, the Mesa and ffmpeg checks (the three artifacts share
      `nix/prebuilt-artifact.nix`), `gradle check`, `gradle integrationTest`, `pnpm test`, the Linux
      e2e journey and the packaged desktop specs stay green; the devenv health check passes and its
      whisper-cli is the artifact.
- [x] (discovered) The pinned asset fetch builds on a Nix that scans fixed-output outputs for
      references: the flat fetch discards them and takes its output path from the structured-attrs
      shell file, so a tar whose bytes name store paths is accepted.

e2e-exempt (ADR 0046): a packaging item that changes no app feature.

## Acceptance criteria

- No shipped x86_64-linux graph compiles whisper-cpp; every graph and the devenv ship the pinned
  asset, on the same 1.8.4 Vulkan/no-SDL version the Gradle path runs; the closure carries no SDL
  chain and does not grow.
- Every platform the flake declares has its pinned whisper-cpp artifact, enforced by a check, so
  platform items ship theirs.
- The Mesa and ffmpeg artifacts keep working through the shared helper; all test-list items ticked
  and green; user approved the diff.

## Approval log

- 2026-10-18: filed as a draft by the root agent at the user's request (item 61's consultation).
  Not yet discussed.
- 2026-10-19 (consultation, confirmed by the user): the approach is the pinned prebuilt artifact
  through `nix/prebuilt-artifact.nix`; every supported platform ships its artifacts (x86_64-linux
  lands here, items 48/49 add theirs); the release tag is `desktop-whisper-cpp-v1`; the 1.8.4
  Vulkan/no-SDL version stands. Test list drafted above; approval pending.

- 2026-10-19 (approval): the user approved the test list as written; Status: spec-approved.

- 2026-10-19 (workspace, completion): all test-list items ticked and green; Status: in-review.

- 2026-10-19 (root-agent review, amendment 1, approved by the user): test-list item 2 is re-opened;
  the shipped artifact is compared against a committed expected transcript captured once from the
  source build (command, store path, model sha256 and date recorded with it), not against the
  source build's live output.
- 2026-10-19 (root-agent review, amendment 2, approved by the user): test-list item 4 is re-opened;
  the asset-pin verification becomes producer apps (`nix run .#ffmpeg-asset-pin` and
  `nix run .#whisper-cpp-asset-pin`; the user chose to move both) so `nix flake check` builds no
  source, while `nix/asset-pin-check.nix` stays shared.
- 2026-10-19 (root-agent review, amendment 3, approved by the user): test-list item 6 and the
  Decided bullet are reworded to the systems the flake declares (Linux now, macOS with item 48);
  Windows is item 49's pinned `versions.nix`/`versions.json` entry, enforced by its tests.

- 2026-10-19 (rework evidence summary, per test-list item): item 1 — `whisper-cpp-everywhere`
  green, cold-store dry-run 0 source hits / 3 whisper drvs; item 2 — `whisper-cpp-behaves` runs the
  artifact only and matches the committed transcript; item 3 — `whisper-cpp-closure-slim`; item 4 —
  `nix run .#ffmpeg-asset-pin` and `nix run .#whisper-cpp-asset-pin` print ok; item 5 —
  `whisper-cpp-artifact-integrity`; item 6 — `whisper-cpp-artifact-policy`; item 7 —
  `whisper-cpp-unregistered-system`; item 8 — `whisper-cpp-closure-size` (default and docker-root
  equal item 62, bundle 1,185 B below); item 9 — CI run 39335806950, desktop job 451 s, no whisper
  build (fetch 51.9 ms, asset 399 ms, re-link 1.67 s); item 10 — CI run 39335806950 green in every
  job at `6eed6fa8`, devenv shell `whisper-cli` = `/nix/store/f9r5…-whisper-cpp-1.8.4`; discovered —
  `whisper-cpp-asset-fetch`. Cold-store dry-run of the check set after the rework: one
  `whisper-cpp-1.8.4.drv` (the re-link), no source; Mesa fetch + re-link only; `ffmpeg-audio-only`
  still builds the ffmpeg source (item 62's build-surface check, not a producer check).

- 2026-10-20 (final head, the user's runs): CI run 39344668758 and CodeQL run 39344668768 are
  green on `742383a9` — every CI job (Nix flake check, Nix build, devenv, backend check and
  integrationTest, frontend, e2e, the three desktop suites) and both CodeQL analyses. The earlier
  CodeQL `java-kotlin` failures were Gradle's default 512 MiB heap thrashing under the tracer; the
  workflow's manual-build step now runs the compile with `-Xmx2g -XX:MaxMetaspaceSize=768m`.

- 2026-10-20 (post-rework, approved by the user): `ffmpeg-audio-only` — item 62's build-surface
  check, which compiles the source — moved from `checks` to `apps.` (`nix run .#ffmpeg-audio-only`),
  so `nix flake check` no longer compiles any ffmpeg source either. `nix flake check` now only
  evaluates the app; the user run prints `ffmpeg-audio-only: ok`.

- 2026-10-20 (final head after the ffmpeg app move, the user's runs): CI run 39347660382 and CodeQL
  run 39347660351 are green on `c62594e8`. `nix flake check` evaluates the three
  producer/build-surface apps (`ffmpeg-asset-pin`, `whisper-cpp-asset-pin`, `ffmpeg-audio-only`)
  and compiles no whisper-cpp, ffmpeg or Mesa source.

- 2026-10-20 (landing): the user approved the full diff (29 commits) and the two comment/spec edits;
  `nix/ffmpeg.nix` now names the producer app, and the Proposal records the producer-app split.
  Squashed onto `main` (`ba7f3130`) as the item's single commit; the PR CI reruns every job on the
  squashed head.
