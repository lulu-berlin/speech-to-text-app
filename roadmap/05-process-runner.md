# 05 — Process runner

Status: in-progress
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done)

## Goal

A small, coroutine-native way to run external tools (ffmpeg, whisper-cli) from the backend: stream their output line by line, capture the exit code, kill them when the owning coroutine is cancelled, and freeze/unfreeze them with `SIGSTOP`/`SIGCONT` so a running transcription can be paused.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): all async work via Kotlin coroutines.
- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pause = `SIGSTOP` the current whisper-cli process, resume = `SIGCONT`.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): external tools are invoked directly, never through Python.

## Proposal (approved 2026-10-06; decisions recorded under Open questions)

- Coroutine wrapper around `ProcessBuilder`: `run(cmd, onStdoutLine, onStderrLine)` starts the process and returns `ProcessResult(exitCode, stdout, stderr)`.
- Every command is spawned as `setsid <cmd…>` so it owns its own session/process group; the direct child's pid is the group id. Signals and kills target the whole group (negative pid) — whole process tree, not just the direct child.
- stdout and stderr are drained concurrently line-by-line on `Dispatchers.IO` (a full pipe buffer never blocks the child); each line is delivered to a callback as written and captured in the result.
- Signals via JNA `kill(2)` (libc): `pause()` = `SIGSTOP`, `resume()` = `SIGCONT`; no external `kill` binary.
- Cancellation: `SIGCONT` first (harmless if not stopped), then `SIGTERM`, wait the grace period, then `SIGKILL`; `run` does not return until the tree is dead; `CancellationException` rethrown. Grace default 2 s, constructor-configurable.
- No raw threads or executors; blocking reads/waits only inside `withContext(Dispatchers.IO)`; 50 ms `waitFor` polling with `ensureActive()`.
- `pause()`/`resume()` on an already-exited process are no-ops.
- Non-existent executables fail fast with a clear error (`ExecutableNotFoundException`, PATH pre-resolution).

## Open questions (answered 2026-10-06)

- **Grace period between `SIGTERM` and `SIGKILL`**: 2 s default, constructor-configurable (integration tests inject ~200–500 ms).
- **Signal delivery**: JNA `kill(2)` (libc binding, `net.java.dev.jna:jna` 5.14.0), decided over spawning coreutils `kill`: no extra process per signal, no PATH dependency at runtime.
- **stdout exposure**: per-line callbacks for both streams + captured stdout/stderr in the result. Rejected `Flow<String>`: an uncollected flow fills the pipe and stalls the child (backpressure deadlock).
- **Signal scope**: the whole process tree. Each run gets its own process group via `setsid` (util-linux); `SIGSTOP`/`SIGCONT`/`SIGTERM`/`SIGKILL` target the group. Developer tooling added to `devenv.nix`; the packaged app (item 16) must ship `setsid`.
- **Buffered lines on cancel**: dropped; the drain coroutines are children of `run` and stop delivering on cancellation.
- **suspend/resume on an exited process**: no-op (`Process.isAlive()` guard; `kill(2)` errors ignored).
- **Cancelling a suspended process**: always `SIGCONT` before `SIGTERM` so a stopped tree still dies on cancel.

## Test list (approved 2026-10-06)

- [x] Running a command that exits successfully returns exit code 0, delivers each stdout line to the callback as it is written, and exposes them in order in the result
- [x] Running a command that fails returns its non-zero exit code and its stderr lines
- [x] Each stderr line is delivered to the `onStderrLine` callback as it is written
- [x] Cancelling a running command (`sleep 100`) kills the process tree, leaves no process behind, and the call returns promptly with `CancellationException`
- [x] A process that ignores SIGTERM is killed forcibly after the grace period
- [x] SIGSTOP freezes the output of a counting shell loop; SIGCONT resumes it from where it stopped
- [x] Cancelling a stopped (SIGSTOP) process tree still terminates it
- [x] Starting a non-existent executable fails with a clear error instead of hanging
- [x] `pause()`/`resume()` on an already-exited process are no-ops
- [x] A final line without a trailing newline is still delivered
- [x] Output larger than the pipe buffer (long `seq`) completes without deadlock
- [ ] (discovered) Lines still buffered when `run` is cancelled are not delivered to the callbacks
- [ ] (discovered) A callback that throws makes `run` fail with that exception and kills the process tree
- [ ] (discovered) Cancelling returns promptly even when a descendant has left the process group and still holds the output pipes

## Acceptance criteria

- Cancelling the coroutine running `sleep 100` leaves no `sleep` process behind (checked via the pid).
- A stopped counting loop produces no new output for a measurable interval and continues after `SIGCONT`.
- Exit code and stderr of a failing command are available to the caller.
- No `Thread`, `ExecutorService` or `CompletableFuture` in the runner code.
- Cancelling a SIGSTOPped process tree still terminates it.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-06: Spec approved. All open questions answered in-session (see above); test list approved with 11 items (seed 8 + exited-no-op, unterminated final line, pipe-overrun; stdout callback streaming folded into item 1). Decisions recorded in ADRs 0020–0021.
- 2026-10-06: Three discovered scenarios (dropped buffered lines on cancel, throwing callback, escaped pipe holder) confirmed by the user at the item-4 checkpoint and appended to the test list.