# 102 — The backend rewritten in Rust, phase 3: uploads and the audio pipeline

Status: spec-approved
Depends on: [100](100-rust-backend-phase-1-foundation.md) — native decoding needs no spawned process
Blocks: [103](103-rust-backend-transcription.md), [104](104-rust-backend-diarization.md) — both consume the chunks and the canonical clip

## Goal

The upload route and the audio pipeline behind it: a multipart body becomes a staged original,
a duplicate check and a `QUEUED` task; the pipeline decodes the original, plans and splits it into
chunks, stores the canonical clip in the database and serves it back through the audio routes.

## Evidence

The area's parity subset: 22 suites, 46 scenarios — the 9 `stt/audio` suites (decode, FLAC
stream info, splitting, chunk planning, silence parsing, the canonical clip channel, the heap
bound, the upload's format table) and 13 `stt/http` suites (the 9 upload suites and the 4 audio
serving suites: download, range, canonical serving, long playback). `UploadMigrationSpec`'s
upgrade scenarios are dropped (item 100's schema decision); `AudioMigrationSpec` likewise.
The wire contract for the upload and audio endpoints is in `docs/api/`.

This item also brings the HTTP skeleton — router, the trusted-origin and credential middleware,
static serving with the SPA fallback — because the upload and audio suites run through real
routes; the remaining routes are item 106's.

## Proposal

1. **Upload**: `multer` driving the route's own loop, with the one-file rule, the configured
   size limit (`413` over it), `400 multiple-files` on a second part, the staged original claimed in the creating
   transaction, the `409 duplicate-audio` race (one `201`, one `409`), and deletion of every
   staged byte on rejection, cancellation or a broken body.
2. **Decode and canonical clip**: a native Rust decoder for the accepted formats and a native
   FLAC encoder for the canonical clip — no ffmpeg, in line with the user's direction that the
   spawned apps are replaced by native implementations where they can be. The header row plus
   one-second rows in `audio_header`/`audio_second` are served as stored.
3. **Chunks**: the planner, the silence parser, the FLAC splitter and the heap bound, with the
   same boundaries the Kotlin pipeline produces so transcripts stay identical.
4. **Serving**: `GET /api/tasks/{id}/audio` and its Range answers straight from the stored rows,
   `404 no-audio` without a clip.

## Decided (consultation, 2026-11-10)

- **Native decoding and encoding, no ffmpeg**: the pipeline decodes with a Rust decoder and
  writes the canonical FLAC with a Rust encoder. This is the same direction as item 99 (native
  whisper): the spawned apps are replaced by native implementations wherever they can be. The
  consequence is below.
- **Staging is a database row, not a directory**: today's staged original lives in
  `original_audio` with a NULL `task_id` (`StagedUpload.kt`), so a crash's leftovers are rows the
  startup removes, not files.
- **Settings come from item 100's repository**: the size limit and the accepted formats are read
  through it, not a second path.
- **Multipart**: `multer` used directly, so the route owns the reading loop — it stops at the size
  limit, at a second file part and on a client disconnect, exactly the paths
  `UploadCleanupSpec` and `UploadLeavesNoBytesSpec` pin. `axum`'s `Multipart` extractor wraps the
  same crate but hides those stop points.
- **Audio fixtures**: the canonical-clip byte fixtures are regenerated once with the native
  encoder and pinned as regression snapshots, and the independent assertions stay alongside them
  — duration, sample count, channels, decodability and the Range answers, derived from the input
  audio, not from the encoder's output.

## Open questions

- **Accepted formats**: ffmpeg's decoder list is the current contract
  (`AllowedExtensionsContractSpec`); a Rust decoder set must cover at least the same formats, or
  the contract narrows deliberately.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list.

- [ ] `audio/AudioChunkerSpec.kt` — 5 scenarios (integration)
- [ ] `audio/AudioDecoderSpec.kt` — 3 scenarios (integration)
- [ ] `audio/CanonicalClipChannelSpec.kt` — 1 scenario
- [ ] `audio/ChunkPlannerSpec.kt` — 8 scenarios
- [ ] `audio/ChunkingHeapBoundIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `audio/FlacSplitterSpec.kt` — 2 scenarios (integration)
- [ ] `audio/FlacStreamInfoSpec.kt` — 1 scenario
- [ ] `audio/ParseSilencesSpec.kt` — 1 scenario
- [ ] `audio/UploadFormatsSpec.kt` — 1 scenario (integration)
- [ ] `http/AudioCanonicalServingSpec.kt` — 1 scenario (integration)
- [ ] `http/AudioDownloadSpec.kt` — 2 scenarios
- [ ] `http/AudioLongPlaybackSpec.kt` — 1 scenario (integration)
- [ ] `http/AudioRangeSpec.kt` — 2 scenarios
- [ ] `http/UploadCleanupSpec.kt` — 1 scenario
- [ ] `http/UploadDuplicateRaceSpec.kt` — 1 scenario
- [ ] `http/UploadDuplicateSpec.kt` — 1 scenario
- [ ] `http/UploadLeavesNoBytesSpec.kt` — 1 scenario
- [ ] `http/UploadMultiFileSpec.kt` — 1 scenario
- [ ] `http/UploadSizeLimitSpec.kt` — 3 scenarios
- [ ] `http/UploadStoredInDatabaseSpec.kt` — 4 scenarios
- [ ] `http/UploadStreamingSpec.kt` — 3 scenarios
- [ ] `http/UploadValidationSpec.kt` — 2 scenarios
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes (the Kotlin backend still
serves). The existing upload and playback journeys are re-run against the Rust backend in item
108.

## Verification and CI

This phase's Rust code is verified locally and is not part of production: `cargo fmt --check`,
`cargo clippy --all-targets -- -D warnings` and `cargo test` at the workspace root (run by the
user), plus the parity suites above. The merge gate is the existing CI unchanged — Kotlin,
frontend, docs and the e2e journeys against the Kotlin backend; no Rust job exists until item
108 adds them.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100); `AudioMigrationSpec` and the
  upgrade scenarios are excluded with the fresh-schema decision.
- 2026-11-10: the user approved the item and its test list, with native decoding and no ffmpeg;
  the multipart library question is still to be answered, and the byte-parity question is
  recorded in Open questions; `spec-approved`.
