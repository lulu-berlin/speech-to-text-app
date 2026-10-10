# Cleanup refactors: test placement, duplication, visibility, toolchain pin

Status: done (roadmap items 20 and 28, merged 2026-10-09; part 2 below)
Depends on: — (touches only merged code from items 02, 04, 05, 06, 07 and the dev environment; independent of 09 and 11)

## Goal

Remove the redundancies and inconsistencies found in the repository review of 2026-10-09 (`main` at `a7753f1`) without changing app behavior, close one test gap in the queue domain model, and pin the development pnpm to the major version the flake builds with.

## Already decided

- [ADR 0012 — Frontend dependency management: pnpm](../decisions/0012-frontend-dependency-management-pnpm.md): the lockfile format is tied to a pnpm major version; the flake uses `pkgs.pnpm_11`.
- [ADR 0015 — Code formatting](../decisions/0015-code-formatting-ktlint-prettier.md): `prettier --check` runs inside `pnpm test`.
- [Backend conventions](../conventions/backend.md): tests are `<Subject>Spec.kt` in the subject's package; all environment access goes through `Config.fromEnv`.
- [Item 04 spec](queue-domain-model.md): illegal transitions are typed rejections, never exceptions.
- Review decisions (user, 2026-10-09): every recommended finding is in scope, plus the cosmetic changes (`data class Config`, `fun main()` without `args`). A generic `QueueOutcome<R>` replacing `QueueCreationOutcome`/`QueueTransitionOutcome`/`ReorderOutcome` is **not** wanted.

## Proposal (approved 2026-10-09)

Except for test-list item 1 (a TDD cycle) and the pnpm pin (a devenv change), everything here is a refactor. Each one keeps all tests green and is approved and committed on its own (`Refactor: …`).

- **R1. Config parsing tests move to `ConfigSpec`.**
  - `transcribe/DeviceArgsSpec.kt` tests `STT_DEVICE` parsing through `Config.fromEnv`.
  - `transcribe/WhisperCommandSpec.kt:56-61` tests the rejection of invalid `STT_THREADS` values.
  - `config/ConfigSpec.kt` covers neither variable, and both transcribe specs define the same `private fun env(vararg) = TEST_REQUIRED_ENV + vars` (`:40`, `:73`).
  - Change:
    - The parsing scenarios move unchanged into `ConfigSpec`, keeping their scenario wording.
    - `DeviceArgsSpec` calls `deviceArgs(Device.Auto / Device.Cpu / Device.Gpu(n))` directly.
    - `WhisperCommandSpec` keeps only the command-building scenarios.
    - Both `env()` helpers disappear.
  - Coverage is unchanged: every scenario still exists exactly once.
- **R2. Audio test helpers reuse `Ffmpeg.kt`.**
  - `audio/TestFixtures.kt:15-18` and `audio/AudioChunkerSpec.kt:163-166` spell out `"ffmpeg", "-hide_banner", "-nostats"` and check `exitCode` by hand.
  - Change: use the existing `internal` `FFMPEG` prefix and `ProcessResult.requireSuccess(what)`, renamed to `expectSuccess` first in its own refactor (user, 2026-10-09).
- **R3. `ProcessRunnerSpec` helpers.**
  - (a) The pause-freeze check (pause, delay 500 ms, assert at most one line arrived meanwhile) appears twice, at `:147-152` and `:447-451`. Change: one private helper.
  - (b) The safety-net cleanup `if (pids.isCompleted) pids.getCompleted().forEach(::killForcibly)` in `finally` appears three times (`:340`, `:375`, `:571`). Change: one helper that runs a block and force-kills the reported pids afterwards.
  - Not merged: where each test captures `aliveWhenRunThrew` (after the throw, inside the catch, or in the cancel handler), because that point differs per scenario.
- **R4. `parseSilences` and `planChunks` become `internal`** (`audio/ParseSilences.kt:12`, `audio/ChunkPlanner.kt:9`): the pure helpers of `AudioChunker` are module-internal, like `FFMPEG` and `expectSuccess` in `audio/Ffmpeg.kt` (corrected 2026-10-09: the original reason, "like every other declaration in `stt.audio`", was wrong).
- **R5. Prettier file list in one place.**
  - The `test` and `format` scripts in `frontend/package.json:10-11` repeat `src index.html vite.config.ts package.json tsconfig.json ../.prettierrc`.
  - Change (decided 2026-10-09):
    - Both scripts run on `. ../.prettierrc`.
    - A new `frontend/.prettierignore` excludes `dist` and `pnpm-lock.yaml`. Prettier skips `node_modules` by default.
    - `pnpm-workspace.yaml` becomes a formatted file.
  - AGENTS.md § Commands names the file list and is updated to match.
- **R6. Cosmetic.**
  - `class Config` becomes `data class Config` (`config/Config.kt`).
  - `fun main(args: Array<String>)` becomes `fun main()` (`Main.kt`). `mainClass` stays `stt.MainKt`.
- **R7. One type for an illegal per-task transition** (decided 2026-10-09: option A).
  - `TaskStatus.transition(to): TransitionOutcome` becomes `TaskStatus.canTransitionTo(to): Boolean`, and `TransitionOutcome.kt` is deleted.
  - `TaskQueue.transition` returns `QueueTransitionRejection.IllegalTransition(from, to)` when `canTransitionTo` is false, as it does today after unwrapping.
  - `TaskStatusSpec` asserts `true`/`false` for every status pair, so the whole table stays covered.
  - Item 04's spec (`docs/specs/queue-domain-model.md:22`) is amended to say that the queue returns the typed rejection for an illegal per-task transition, and the amendment is recorded in that spec's Approval log.
  - Item 12 can check a stored status against the table with the same Boolean (terminal tasks are no longer in the queue).
- **pnpm pin.**
  - `devenv.nix:32` uses unpinned `pkgs.pnpm`, so a nixpkgs bump can move development to a newer pnpm major while the flake stays on `pkgs.pnpm_11`.
  - Change:
    - `devenv.nix` uses `pkgs.pnpm_11` (its own commit, ADR 0010).
    - ADR 0012 is amended: dev and flake pin the same pnpm major, and a major upgrade changes both together.
  - Verified by the user in a re-entered shell.

## Open questions (answered 2026-10-09)

- R7: merge `TransitionOutcome.Rejected` and `QueueTransitionRejection.IllegalTransition`? → **(A) a Boolean per-task check, plus the spec amendment above.** Not chosen: (B) one class implementing both sealed interfaces, which avoids the spec change but puts one class in two hierarchies; (C) keep both types.
- R5: how to keep a single list of formatted files? → **`prettier . ../.prettierrc` with a new `frontend/.prettierignore`.**

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] A status change through the queue that the per-task table forbids (e.g. `QUEUED → COMPLETED`, `PAUSED → RUNNING`) is rejected as an illegal transition naming both statuses, and the queue is unchanged
- [x] Must not break: after every refactor, `gradle check`, `gradle integrationTest`, `pnpm test` and `pnpm build` are green, and the backend test count changes only by the new test above and by the two `ConfigSpec` scenarios R1 splits out of mixed scenarios (amended 2026-10-09, discovered)
- [x] Must not break (user-verified): in the re-entered devenv shell `pnpm --version` reports 11.x and `pnpm install --frozen-lockfile` succeeds

## Acceptance criteria

- R1–R7 done, each as its own approved `Refactor:` commit; the pnpm pin as its own `devenv.nix` commit.
- No Config parsing scenario remains outside `ConfigSpec`, and none was lost in the move.
- `devenv.nix` and `flake.nix` pin the same pnpm major, as recorded in amended ADR 0012.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item created from the repository review; scope set by the user: all recommended findings plus the cosmetic changes; generic `QueueOutcome<R>` rejected. Spec and test list not yet approved.
- 2026-10-09: decisions: R7 = option A (Boolean `canTransitionTo`, item 04 spec amended); R5 = `prettier . ../.prettierrc` + `.prettierignore`. Test list not yet approved.
- 2026-10-09: spec and test list approved by the user (3 items); status `spec-approved`.
- 2026-10-09: baseline on `5c940ca` (reference for test-list item 2): `gradle check` green, 105 tests in 34 specs, 0 failed; `gradle integrationTest` green, 44 Integration tests run, 0 failed (149 reported, the 105 untagged ones skipped); `pnpm test` green, 1 test in 1 file, prettier clean; `pnpm build` green.
- 2026-10-09: test-list item 1 done (`TaskQueueSpec`, user-confirmed). Green on arrival; red recorded against a temporary, uncommitted mutation making `TaskQueue.transition` skip the per-task check: `expected:<Rejected(reason=IllegalTransition(from=QUEUED, to=COMPLETED))> but was:<Accepted(queue=TaskQueue(entries=[QueueEntry(id=2, status=PAUSED)], paused=false))>`; mutation reverted.
- 2026-10-09 (discovered, confirmed by the user before R1): R1 splits the parsing part out of the mixed `DeviceArgsSpec`/`WhisperCommandSpec` scenarios into two new `ConfigSpec` scenarios (`STT_DEVICE`, `STT_THREADS`), so the backend count rises by two more; test-list item 2 amended accordingly.
- 2026-10-09: R2 approved with one change by the user: `ProcessResult.requireSuccess` is renamed to `expectSuccess` (production function and its three callers), as its own `Refactor:` commit before R2.
- 2026-10-09 (discovered before R4, user chose option 1): R4's reason was wrong; `stt.audio` also has public declarations (`planChunks`, `AudioChunker`, `AudioDecoder`, the data classes). R4 widened to `planChunks`, the other pure helper called only by `AudioChunker`; reason corrected in the R4 bullet.
- 2026-10-09: R1–R7 done, each approved by the user and committed on its own (plus the approved `expectSuccess` rename). pnpm pin committed (`devenv.nix` alone, then the ADR 0012 amendment); awaiting the user's check in the re-entered worktree shell (test-list item 3).
- 2026-10-09: test-list item 2 done. Final runs on `cd191e7` with `--rerun-tasks`: `gradle check` green, 108 tests in 34 specs, 0 failed (baseline 105 + 1 from test-list item 1 + 2 from the R1 split); `gradle integrationTest` green, 44 Integration tests run, 0 failed (as baseline); `pnpm test` green, 1 test in 1 file, prettier clean over the R5 file set; `pnpm build` green. Every refactor commit was checked with all four commands before committing.
- 2026-10-09: test-list item 3 done (user-verified in the worktree's devenv shell after `direnv allow`): `pnpm --version` → `11.25.0`; `pnpm install --frozen-lockfile` → "Lockfile is up to date, resolution step is skipped", 108 packages added, "Done in 1.5s using pnpm v11.25.0". The two attempts before it failed with `EAGAIN` on `rmdir`/`rename` inside `node_modules/.pnpm` (virtiofs mount, while replacing a `node_modules` tied to the agent sandbox's pnpm store); the lockfile was accepted in both. Status `in-review`.
- 2026-10-09: merged into `main` (head `78edfd0`) after the user approved the diff; spec and final test list moved here from `roadmap/20-cleanup-refactors.md`.

---

## Part 2 — item 28: post-merge cleanup (merged 2026-10-10)

### Goal

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

- [x] The upload allow-list has one source: backend and frontend both pin `docs/api/allowed-extensions.json`, and an extension inside/outside the list still behaves as before (backend 400 `unsupported-format`; frontend row error before any request)
- [x] The delete flow runs through one shared dialog-action machine: the History list, the History detail (status page and transcript) and the queue rows confirm, cancel on Escape, show the inline error and remove the task on done exactly as today
- [x] Command responses update the store through one mechanism: a successful pause/resume/cancel/retry still lands the returned task in the live state, and a retry from the status page still navigates to `/queue`
- [x] The engine's event buffer and the SSE stream's `BUFFER` are one constant: changing one is a compile error, not a silent divergence
- [x] Engine startup resolves the engine once: the module starts the engine it created at startup and no code path resolves the engine twice
- [x] (must not break) Everything above is refactor-only: `gradle check`, `gradle integrationTest`, `pnpm test` and `pnpm build` stay green, and no existing test changes (they are the behavior proof)

## Acceptance criteria

- `docs/api/allowed-extensions.json` exists and is pinned on both sides; no side-only copy remains.
- `deleteTaskMachine` and `useTaskCommand` are gone; the shared machine and command path have exactly the coverage of what they replace.
- No behavior change: all suites green, no test was weakened or copied from actual values.
- The user approved the diff; no new dependencies.

### Approval log

- 2026-10-10: drafted by the root agent after the codebase sweep (evidence above); the user approved the scope (findings 1–3 and 5–6; findings 4 and the time-formatting hoist recorded for item 25) and the test list; status `spec-approved`.
- 2026-10-10: workspace agent completed all six test-list items — mechanism (allowed-extensions.json contract pin; `confirmActionMachine`; `commandMachine` for the status-page retry; `EVENT_BUFFER` constant; EngineModule single resolve) was confirmed by the user before implementation. `gradle check`, `gradle integrationTest`, vitest + prettier + tsc + vite build all green; no existing test changed. Status `in-review`.
- 2026-10-10: merged into `main` (head `e22a4e4`) after the user approved the diff. All six
  test-list items green with zero test churn (228/228 backend, 49/49 integration, 120/120
  frontend): one allow-list pinned on both sides, one shared `confirmActionMachine`,
  `commandMachine` for the status-page retry (`useTaskCommand` deleted), one `EVENT_BUFFER`
  constant, the engine resolved once. Item 28's spec is appended here as part two.

