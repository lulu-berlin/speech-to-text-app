# 108 — The backend rewritten in Rust, phase 9: the switch

Status: spec-approved
Depends on: [107](107-rust-backend-app-mode.md) — and every earlier phase, all green
Blocks: —

## Goal

Make the Rust service the product: packaging and every dev path run `stt-service`, CI gates it,
the full parity checklist and the 50 e2e journeys pass against it, and the Kotlin backend, its
Gradle build and its CI jobs are deleted in the same change.

## Evidence

The parity gate in [`docs/backend-parity-tests.md`](../docs/backend-parity-tests.md) §1 is the
acceptance list, unchanged: every unit suite at its count, every integration suite with the real
ffmpeg, whisper-cli and CAM++ model, the 41 wire examples byte for byte, the 50 journeys, and a
successor for each backend-aware docs check. Nothing of the Kotlin reference remains to compare
against afterwards, so this is the one item that must not be merged on a partial green.

## Proposal

1. **Packaging**: the Nix derivation, the desktop bundles and `devenv up` start the Rust binary;
   the model paths, ffmpeg and whisper-cli stay as they are; the frontend build is unchanged.
2. **CI**: add the Rust jobs (`cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`,
   `cargo test --workspace` on Linux, macOS and Windows, the integration-tagged parity suites on
   the platforms they need, the e2e journeys against the Rust backend); remove the Kotlin jobs
   (`gradle check`, `gradle integrationTest`, the Kotlin parts of the packaged jobs) and
   `backend/deps.json`.
3. **The docs checks**: `check-exceptions` (no broad `catch` without the marker), the
   architecture overview's names and diagram, the source scans and `checkVersions` get their Rust
   successors, as §3 of the parity document requires.
4. **Deletion**: `backend/` (Kotlin, Gradle, Flyway resources), the Gradle wrapper's role, the
   Kotlin rows in `devenv.nix`/`flake.nix`/`versions.json`, and every doc, README and command
   that names Gradle; the API examples stay the contract.
5. **The checklist**: the parity document's counts become the Rust suite counts; the exceptions
   recorded in item 100 (`AudioMigrationSpec`/upgrade scenarios, `TaskQueueSpec`'s aggregate) are
   restated as permanent, not temporary.

## Open questions

- **One atomic PR — settled**: the packaging, the Rust CI jobs, the e2e against Rust and the
  deletion of the Kotlin backend with its Gradle build and CI jobs land together, so there is
  never a state where both backends are maintained.
- **The packaged desktop jobs**: they build the backend today; after the switch they build the
  Rust binary for each target, which changes the matrix's wall time (item 85's work shapes it).
- **What the bundles still ship**: with native decoding (item 102), the ONNX runtime (item 104)
  and item 99's native whisper, ffmpeg and whisper-cli may leave the runtime assets; which of
  them remain is settled by the phases' outcomes, and this item removes only what is gone.
- **The data directory's continuity**: existing `stt.db` files at schema 17 keep working (item
  100); confirm nothing else in the data directory (scratch, staged uploads, the port file) must
  be migrated.
- **Deleting the reference**: the Kotlin suite is the second proof until this item merges; after
  the merge the Rust suites are the only proof. The parity document's role afterwards (delete it
  or keep it as the historical checklist) needs the user's call.

## Test list

Draft. The acceptance is the parity gate, written as checkable lines:

- [ ] Every unit suite in the checklist passes against `stt-service` at its listed count.
- [ ] Every integration suite in §5 passes with the real ffmpeg, whisper-cli and CAM++ model.
- [ ] The 41 wire examples match byte for byte and the OpenAPI document still describes the
      service.
- [ ] The 50 e2e journeys pass against `stt-service` serving the built UI.
- [ ] `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` and `cargo test
      --workspace` are green on Linux, macOS and Windows in CI.
- [ ] The packaged desktop app builds on every target and its journeys pass.
- [ ] Every backend-aware docs check has its successor and the checks pass on the Rust tree.
- [ ] The Kotlin backend, Gradle files, Python-free build scripts and CI jobs are gone, and no
      doc or command mentions them.
- [ ] (must not break) The frontend, docs and client jobs stay green.

## e2e stance

This item owns the e2e proof: the existing journeys (no new ones — the rewrite adds no feature)
run against the Rust backend, and the packaging jobs exercise the desktop app.

## Verification and CI

This is the item that puts the Rust code into CI and production; everything it adds is
user-run until the PR's own run is green, and that run is the merge gate.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100).
- 2026-11-10: the user approved the item and its test list, with the atomic one-PR switch;
  `spec-approved`.
