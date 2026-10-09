# 20 — Cleanup refactors: test placement, duplication, visibility, toolchain pin

Status: in-progress
Depends on: — (touches only merged code from items 02, 04, 05, 06, 07 and the dev environment; independent of 09 and 11)

## Goal

Remove the redundancies and inconsistencies found in the repository review of 2026-10-09 (`main` at `a7753f1`) without changing app behavior, close one test gap in the queue domain model, and pin the development pnpm to the major version the flake builds with.

## Already decided

- [ADR 0012 — Frontend dependency management: pnpm](../docs/decisions/0012-frontend-dependency-management-pnpm.md): the lockfile format is tied to a pnpm major version; the flake uses `pkgs.pnpm_11`.
- [ADR 0015 — Code formatting](../docs/decisions/0015-code-formatting-ktlint-prettier.md): `prettier --check` runs inside `pnpm test`.
- [Backend conventions](../docs/conventions/backend.md): tests are `<Subject>Spec.kt` in the subject's package; all environment access goes through `Config.fromEnv`.
- [Item 04 spec](../docs/specs/queue-domain-model.md): illegal transitions are typed rejections, never exceptions.
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
- [ ] Must not break: after every refactor, `gradle check`, `gradle integrationTest`, `pnpm test` and `pnpm build` are green, and the backend test count changes only by the new test above and by the two `ConfigSpec` scenarios R1 splits out of mixed scenarios (amended 2026-10-09, discovered)
- [ ] Must not break (user-verified): in the re-entered devenv shell `pnpm --version` reports 11.x and `pnpm install --frozen-lockfile` succeeds

## Acceptance criteria

- R1–R7 done, each as its own approved `Refactor:` commit; the pnpm pin as its own `devenv.nix` commit.
- No Config parsing scenario remains outside `ConfigSpec`, and none was lost in the move.
- `devenv.nix` and `flake.nix` pin the same pnpm major, as recorded in amended ADR 0012.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item created from the repository review; scope set by the user: all recommended findings plus the cosmetic changes; generic `QueueOutcome<R>` rejected. Spec and test list not yet approved.
- 2026-10-09: decisions: R7 = option A (Boolean `canTransitionTo`, item 04 spec amended); R5 = `prettier . ../.prettierrc` + `.prettierignore`. Test list not yet approved.
- 2026-10-09: spec and test list approved by the user (3 items); status `spec-approved`.
- 2026-10-09: baseline on `da7bd63` (reference for test-list item 2): `gradle check` green, 105 tests in 34 specs, 0 failed; `gradle integrationTest` green, 44 Integration tests run, 0 failed (149 reported, the 105 untagged ones skipped); `pnpm test` green, 1 test in 1 file, prettier clean; `pnpm build` green.
- 2026-10-09: test-list item 1 done (`TaskQueueSpec`, user-confirmed). Green on arrival; red recorded against a temporary, uncommitted mutation making `TaskQueue.transition` skip the per-task check: `expected:<Rejected(reason=IllegalTransition(from=QUEUED, to=COMPLETED))> but was:<Accepted(queue=TaskQueue(entries=[QueueEntry(id=2, status=PAUSED)], paused=false))>`; mutation reverted.
- 2026-10-09 (discovered, confirmed by the user before R1): R1 splits the parsing part out of the mixed `DeviceArgsSpec`/`WhisperCommandSpec` scenarios into two new `ConfigSpec` scenarios (`STT_DEVICE`, `STT_THREADS`), so the backend count rises by two more; test-list item 2 amended accordingly.
- 2026-10-09: R2 approved with one change by the user: `ProcessResult.requireSuccess` is renamed to `expectSuccess` (production function and its three callers), as its own `Refactor:` commit before R2.
- 2026-10-09 (discovered before R4, user chose option 1): R4's reason was wrong; `stt.audio` also has public declarations (`planChunks`, `AudioChunker`, `AudioDecoder`, the data classes). R4 widened to `planChunks`, the other pure helper called only by `AudioChunker`; reason corrected in the R4 bullet.
