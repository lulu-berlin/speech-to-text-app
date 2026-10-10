# 28 — Post-merge cleanup refactors

Status: in-progress
Depends on: —

## Goal

Remove the redundancies the codebase sweep found after items 12, 14, 15, 17, 22, 24, 26 and 27 merged, with zero behavior change: one shared upload allow-list with a contract pin, one shared dialog-action machine, one command mechanism, and two small backend tidy-ups.

## Evidence (root agent sweep, 2026-10-10)

1. **Upload allow-list duplicated and unpinned.** The 20-extension list is verbatim in `backend/src/main/kotlin/stt/http/UploadRequest.kt` (`ALLOWED_EXTENSIONS`) and `frontend/src/features/queue/uploadValidation.ts`, with no test binding them — unlike `languages.json`, which `LanguagesContractSpec` pins to `docs/api/`. A side-only edit silently diverges until item 21's E2E.
2. **Two near-identical delete flows.** `features/history/deleteTaskMachine.ts` (item 15) and the delete path of `features/queue/rowActionMachine.ts` (item 14) both implement idle → confirming → running → done|failed around `deleteTask`, with the error in `context.error`. Both predate the state-convention ruling (ADR 0038) and were built in parallel.
3. **Two command-running mechanisms.** Item 15's `useTaskCommand` spawns a fresh `commandMachine` actor per press and pushes `task-updated` from its subscription; item 14's rows run commands inside `rowActionMachine` and push `task-updated` from effects. The "on done → store update" logic exists in both shapes.
4. **`Skeleton` has zero consumers** — dead since items 14/15/17 merged (15 ships "Loading…" text, 17 made the mascot the loading indicator). **Not this item's:** item 25's loading-state rework may delete it; the time-formatting hoist (`formatFinishDate`/`formatDuration` out of `HistoryRow` into a shared module) also belongs to 25, which will need them in the transcript header. Both recorded here so they are not lost.
5. **SSE buffer "64" is a doubled literal.** `ProcessingEngine.kt` `MutableSharedFlow(extraBufferCapacity = 64)` and `EventStream.kt` `const val BUFFER = 64` coordinate by convention only.
6. **`EngineModule` resolves the engine twice.** `require(DependencyKey<ProcessingEngine>())` creates it at startup; the trailing `launch { dependencies.resolve<ProcessingEngine>().start() }` resolves the same cached instance again.

## Proposal (approved 2026-10-10)

- **Shared allow-list, pinned (finding 1).** A contract data file `docs/api/allowed-extensions.json` (a JSON array, like `languages.json`) becomes the single source. The backend keeps a packaged copy (the app must ship it) pinned to the contract file by a `LanguagesContractSpec`-style test; the frontend reads or pins the same file. No behavior change: the same 20 extensions, the same `unsupported-format` errors on both sides. The workspace proposes the exact mechanism at its first checkpoint.
- **One shared dialog-action machine (finding 2).** A machine in `src/shared/state/` (e.g. `confirmActionMachine`) implements idle → confirming → running → done|failed over an action input; the queue's `rowActionMachine` keeps the command set (pause/resume/cancel/delete) and delegates the dialog/delete lifecycle; `deleteTaskMachine` is deleted. The History list and detail delete flows use the shared machine. All existing delete tests stay green unchanged (they are the proof of no behavior change).
- **One command mechanism (finding 3).** The ad-hoc `useTaskCommand` actor spawns are replaced by the shared command path; on done, the store still receives the returned task, and the status page's retry still navigates to `/queue`.
- **Backend tidy-ups (findings 5–6).** The engine's event-flow buffer and the stream's `BUFFER` are bound to one constant (compile-time); `EngineModule` calls `start()` on the engine it already resolved, without a second resolve.
- Out of scope, recorded for item 25: `Skeleton` deletion, the shared time-formatting module.

## Open questions (answered 2026-10-10)

- Where does the allow-list live? → `docs/api/allowed-extensions.json`, pinned on both sides (mirrors languages.json).
- New ADR? → Not expected; the consolidation honors ADR 0038 (machines) and ADR 0014/0029 (catalog/contract pins). If the workspace finds a lasting convention to record, the next free number is 0043.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-10 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The upload allow-list has one source: backend and frontend both pin `docs/api/allowed-extensions.json`, and an extension inside/outside the list still behaves as before (backend 400 `unsupported-format`; frontend row error before any request)
- [ ] The delete flow runs through one shared dialog-action machine: the History list, the History detail (status page and transcript) and the queue rows confirm, cancel on Escape, show the inline error and remove the task on done exactly as today
- [ ] Command responses update the store through one mechanism: a successful pause/resume/cancel/retry still lands the returned task in the live state, and a retry from the status page still navigates to `/queue`
- [ ] The engine's event buffer and the SSE stream's `BUFFER` are one constant: changing one is a compile error, not a silent divergence
- [ ] Engine startup resolves the engine once: the module starts the engine it created at startup and no code path resolves the engine twice
- [ ] (must not break) Everything above is refactor-only: `gradle check`, `gradle integrationTest`, `pnpm test` and `pnpm build` stay green, and no existing test changes (they are the behavior proof)

## Acceptance criteria

- `docs/api/allowed-extensions.json` exists and is pinned on both sides; no side-only copy remains.
- `deleteTaskMachine` and `useTaskCommand` are gone; the shared machine and command path have exactly the coverage of what they replace.
- No behavior change: all suites green, no test was weakened or copied from actual values.
- The user approved the diff; no new dependencies.

## Approval log

- 2026-10-10: drafted by the root agent after the codebase sweep (evidence above); the user approved the scope (findings 1–3 and 5–6; findings 4 and the time-formatting hoist recorded for item 25) and the test list; status `spec-approved`.