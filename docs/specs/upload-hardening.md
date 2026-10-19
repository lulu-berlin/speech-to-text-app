# 41 — Upload hardening: duplicate race, size limit, spool cleanup

Status: done (roadmap item 41, approved 2026-10-18)
Depends on: [12](http-api-and-sse.md) (done; the upload endpoint)
Blocks: —

## Goal

Two simultaneous identical uploads produce exactly one task and one consistent `409 duplicate-audio`
answer naming the winner (never a raw 500); an oversized or malformed upload is rejected while
streaming with a clear error, and no request — successful, rejected or cancelled — leaves a staged
original behind.

## Evidence

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against
  the code (the finding held); re-read 2026-10-18 after items 12 and 44 changed the upload path:
  - **Duplicate race still present**: `UploadRoutes.kt:79` pre-checks `audio.taskIdOfSha256`, then
    `:96` inserts through `TaskRepository.createFromStaged` (`:63-69`), which does not translate a
    constraint violation. `audio.sha256` is `UNIQUE` (`V1__init.sql:30`), so a concurrent identical
    upload surfaces as a raw database failure → `500`, not the contract's `409 duplicate-audio`.
  - **No size limit**: nothing caps the bytes; `StagedUpload.stageChannel` reads unbounded 1 MiB
    windows into `original_audio` parts. Since item 44 the spool is database rows, not `tmp/` files
    (the 2026-10-12 wording predates it).
  - **Multi-part overwrite and cleanup**: the multipart loop (`UploadRoutes.kt:44-63`) stages each
    file part into `staged`, so a second file part overwrites the first; only the last is discarded
    in the `finally` (`:104-107`). The staging loop also sits **outside** the guarded `try`, so a
    read failure or client disconnect during it leaks the staged original until the startup sweep
    (`StagedUpload.discardUnclaimedStaged`).
- [ADR 0046](../decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e
  assertion for the duplicate-race behavior.
- The frontend already handles `duplicate-audio` inline (a link to the existing task) and
  `unsupported-format`; other error codes render the server's message
  (`frontend/src/features/queue/QueueView.tsx:177`, `:206`;
  `uploadBatchMachine.ts:81`).

## Decided (consultation, 2026-10-18)

- **The size limit is configuration** (the user): `STT_MAX_UPLOAD_BYTES` in `Config.kt`, default
  1 GiB, documented in the README's environment table; tests set a tiny value. An upload over it is
  rejected with `413` and the new error code `upload-too-large`, whose message names the limit;
  the check runs from `Content-Length` when present (before staging) and while streaming for
  chunked bodies. No task is created and the staged bytes are deleted.
  The limit bounds the file part's bytes; `Content-Length` also counts the multipart envelope, so a
  request that declares a body over the limit is refused outright — an upload of exactly the limit is
  accepted when it arrives chunked (decided 2026-10-18, option a).
- **Duplicates keep the pre-check and add a translated constraint** (the user): the fast
  `409 duplicate-audio` stays; `createFromStaged` catches the unique violation, reads the winning
  task by sha256 and answers the same `409 duplicate-audio` naming it. The unique constraint stays
  the authority; an integration test holds a request between the check and the insert to pin the
  race deterministically.
- **A second file part is rejected** (the user): as soon as it arrives, the first staged upload is
  discarded and the request answers `400` with the new code `multiple-files`; one file per upload,
  and the first is never kept.
- **Everything is guarded, and the duplicate race gets an e2e** (the user): all staging moves inside
  one guarded scope; every path — success, rejection, cancellation, mid-stream failure — discards
  the staged original under `NonCancellable`, and an integration test asserts no unclaimed original
  remains after each. The e2e sends two concurrent identical uploads and asserts exactly one `201`
  and one `409`, never a `500`; the exact interleaving is pinned by the integration seam.

## Proposal

1. `Config`: `STT_MAX_UPLOAD_BYTES` (default 1 GiB), validated (positive); the route takes it from
   `config`.
2. `UploadRoutes`: reject `Content-Length` over the limit before staging; enforce the limit while
   reading (both `Content-Length` and chunked bodies); reject a second file part (`multiple-files`);
   move staging inside the guarded scope.
3. `StagedUpload`: a limit-aware staging read that fails with `upload-too-large` before writing a
   window that would exceed the limit, discarding what it staged.
4. `TaskRepository.createFromStaged`: translate the `audio.sha256` unique violation into a
   duplicate result naming the winner; the repository (or a small result type) reports it so the
   route answers `409 duplicate-audio`.
5. API contract: the two new error codes in `docs/api/README.md` and an `error.upload-too-large.json`
   example; the frontend shows their server messages through the existing generic path.
6. README: the new environment variable.

## Test list (approved 2026-10-18; behaviors only)

- [x] An upload larger than the configured limit is rejected with `413 upload-too-large` naming the
      limit, no task is created and no staged original remains — for a declared `Content-Length`
      and for a chunked body without one
- [x] An upload of exactly the limit is accepted
- [x] Two simultaneous identical uploads answer exactly one `201` and one `409 duplicate-audio`
      naming the winning task — never a `500`; the losing request leaves no staged original (the
      integration seam holds the request between the check and the insert)
- [x] A multipart body carrying a second file part is rejected with `400 multiple-files`, the first
      file is not kept and no staged original remains
- [x] Every staged original is deleted on success, on rejection, on cancellation (client
      disconnect) and on a mid-stream failure; no unclaimed original remains after each path
- [x] The new error codes are in the API contract docs and the queue view renders their server
      messages inline, leaving no row and no task
- [x] (e2e, ADR 0046) Against the real backend, two concurrent identical uploads leave one task and
      show one `201` and one `409` (the duplicate message with its link), never a `500`
- [x] (discovered) `STT_MAX_UPLOAD_BYTES` defaults to 1 GiB, and a zero, negative or non-numeric
      value fails startup naming the variable
- [x] (must not break) The upload suites (`UploadDuplicateSpec`, `UploadStreamingSpec`, …), the rest
      of `gradle check`, the frontend suites and the existing e2e journey stay green; the
      extension-only acceptance row in `docs/known-issues.md` stays open (out of scope)

## Acceptance criteria

- The upload path is atomic at the app level for duplicates, bounded for size, rejects a second
  file part, and never leaves a staged original behind on any path.
- The new error codes are contract data and appear in the UI without special-casing.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 5 after code triage (no size cap,
  multi-part spool overwrite, un-translated unique conflict).
- 2026-10-18: evidence re-read after items 12/44 (the race and the missing limit still hold; the
  spool is now database rows and the staging loop is outside the guarded scope).
- 2026-10-18: consultation — decisions: an env-configured size limit (`STT_MAX_UPLOAD_BYTES`,
  1 GiB default, `413 upload-too-large`); the duplicate pre-check plus a translated unique violation
  (constraint stays the authority); a second file part rejected as `400 multiple-files`; all
  staging guarded, cleanup under `NonCancellable`, and an e2e with two concurrent identical uploads
  (one `201`, one `409`, never `500`). Test list approved as worded above. Status: spec-approved.
  Filed on `main` through a docs PR.

- 2026-10-18: implementation — the configurable limit (`STT_MAX_UPLOAD_BYTES`, 1 GiB), the duplicate
  translation at `audio.sha256`, the `multiple-files` refusal and one globally guarded multipart
  loop. Discoveries: an upload of exactly the limit is accepted only chunked (a declared
  `Content-Length` counts the multipart envelope), and a staging-loop failure could not be produced
  through the Ktor test client (parser failures land inside the part channel that `stageChannel`
  already cleans), so cancellation is pinned through the `afterFilePartStaged` test seam. Status:
  in-review.

- 2026-10-18: root-agent review and the user's approval of the diff ("Let's land it"), the
  discovered config-validation test item included. Verified: `gradle check`, the four new upload
  specs, the frontend suite with prettier and tsc, and the docs link check; the e2e spec was
  reviewed but not run here. Landed as the item's single squash commit.
