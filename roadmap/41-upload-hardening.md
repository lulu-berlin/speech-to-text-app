# 41 — Upload hardening: duplicate race, size limit, spool cleanup

Status: draft
Depends on: [12](../docs/specs/http-api-and-sse.md) (done; the upload endpoint)
Blocks: —

## Goal

Two simultaneous identical uploads produce exactly one task and one consistent `409 duplicate-audio` answer (never a raw 500); an oversized or malformed upload is rejected while streaming, and no request ever leaves a temporary spool behind.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Evidence (`UploadRoutes.kt`): the duplicate decision is a pre-check (`audio.taskIdOfSha256`) followed by a separate insert (`createFromSpooled`) with no catch — the database's unique constraint is the only safeguard, so a concurrent identical upload surfaces as a raw constraint failure (500), not the contract's `409 duplicate-audio`. There is no application-level maximum upload size (bytes stream to disk window by window, unbounded). A multipart body with more than one file part overwrites the `spooled` reference, and the `finally` deletes only the last one — earlier spools stay in `tmp/` until the startup sweep.
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): the fix carries an e2e assertion for the duplicate-race behavior.

## Proposal

To be settled in the consultation. Starting point: make the create path translate a uniqueness conflict into the normal `409 duplicate-audio` naming the winning task (keep the constraint as the final safeguard); enforce a maximum upload size while streaming (a clear error, no partial task, spool deleted); reject a request whose body carries more than one file part; track every spool a request creates and guarantee cleanup on success, rejection and cancellation.

## Open questions (to be answered in the consultation)

- The size limit's default (e.g. 1 GiB?) and whether it is configuration or a constant.

## Test list (proposed; behaviors only)

- [ ] Two simultaneous identical uploads answer one `201` and one `409 duplicate-audio` naming the winning task — never a 500
- [ ] An upload larger than the limit is rejected cleanly while streaming (no task, clear error, spool gone)
- [ ] A multipart request with more than one file part is rejected, and no temporary file from it remains
- [ ] Every spool is deleted on success, on rejection and on a cancelled upload
- [ ] (e2e, ADR 0046) Against the real backend, the duplicate-race path behaves per the contract under concurrency
- [ ] (must not break) The upload suites (`UploadDuplicateSpec`, `UploadStreamingSpec`, …) and the e2e journey stay green

## Acceptance criteria

- The upload path is atomic at the app level for duplicates and bounded for size, and never leaks a spool; the suites and the e2e journey are green.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed from the third-party review's finding 5 after code triage (no size cap, multi-part spool overwrite, un-translated unique conflict).