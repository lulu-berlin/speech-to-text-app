# 101 — The backend rewritten in Rust, phase 2: process control

Status: spec-approved
Depends on: [100](100-rust-backend-phase-1-foundation.md)
Blocks: [103](103-rust-backend-transcription.md), [105](105-rust-backend-engine-and-events.md) — the whisper caller (and anything else that stays spawned) is built on this

## Goal

The process layer the rest of the rewrite spawns through: start a child with a clean environment,
read its stdout lines as they arrive, stop it with the right signal (and its whole tree), and never
leak a process or block the runtime. Ported from `stt/process` and green at every scenario.

## Evidence

[`docs/backend-parity-tests.md`](../docs/backend-parity-tests.md) lists the area: 8 suites, 41
scenarios, 2 of them white-box (`ProcessRunnerLibcSpec`, `ProcessRunnerStoppingSpec`) and 4
Windows-only (`ProcessRunnerWindowsSpec`). `ProcessRunnerSpec`'s 24 integration scenarios drive
real processes, including a stuck child that must be killed. The reimplementation guide's
§11 (process control) is the behavior: groups and `setsid`/`taskkill /T`, the stopping flag, the
grace period, line reading without losing the tail.

## Proposal

1. **Runtime**: `tokio::process` with the same environment and working directory as today; on
   Unix `pre_exec` calls `setsid` so the child leads its own process group; on Windows the child
   runs in a job object so the tree dies together.
2. **Reading**: an async line reader over stdout/stderr that yields lines in order, keeps a
   bounded buffer, and closes cleanly when the child exits — the whisper progress parser (item
   103) and the ffmpeg decoder (item 102) both consume it.
3. **Stopping**: a stopping flag that sends the group `SIGTERM`, waits the grace period, then
   `SIGKILL`; Windows sends `Ctrl-Break`/`taskkill /T /F` as the Kotlin runner does. A stopped
   child is reaped, never a zombie, and the stop is idempotent.
4. **Ports and proofs**: the same suite names (`process_runner.rs`, `process_tree.rs`, …); the
   Windows and macOS behaviors are proven on those platforms' CI legs.

## Decided (consultation, 2026-11-10)

- **Windows**: the child runs in a job object, so the whole tree dies together.
- **Unix**: `setsid` + `killpg`, as today (no pidfd path).
- **Grace period**: 2 s, the value the Kotlin runner uses (`ProcessRunner.kt:34`), and the
  stuck-child test waits it out rather than using a fake clock.

## Open questions

- **The test clock and process waits**: whether item 100's scheduler seam may replace the real
  grace-period wait in the engine suites (it cannot for `ProcessRunnerSpec`, which drives a real
  child), and how the engine suites keep their runtime bounded.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list for
that suite.

- [ ] `process/ProcessRunnerLibcSpec.kt` — 2 scenarios
- [ ] `process/ProcessRunnerPlatformSpec.kt` — 4 scenarios
- [ ] `process/ProcessRunnerSpec.kt` — 24 scenarios (integration)
- [ ] `process/ProcessRunnerStoppingSpec.kt` — 2 scenarios
- [ ] `process/ProcessRunnerWindowsSpec.kt` — 4 scenarios
- [ ] `process/ProcessTreeSignalsSpec.kt` — 2 scenarios
- [ ] `process/ProcessTreeSpec.kt` — 1 scenario
- [ ] `process/ReadLinesSpec.kt` — 2 scenarios
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: the process layer is internal and no app behavior
changes; its real-process integration suite is the proof.

## Verification and CI

This phase's Rust code is verified locally and is not part of production: `cargo fmt --check`,
`cargo clippy --all-targets -- -D warnings` and `cargo test` at the workspace root (run by the
user), plus the parity suites above. The merge gate is the existing CI unchanged — Kotlin,
frontend, docs and the e2e journeys against the Kotlin backend; no Rust job exists until item
108 adds them.

## Approval log

- 2026-11-10: filed as a draft by the root agent with the rewrite's plan (item 100); process
  control is phase 2 because the whisper caller is built on it.
- 2026-11-10: the user approved the item and its test list; Windows job objects, Unix
  `setsid` + `killpg`, the 2 s grace period; `spec-approved`.
