# 05 — Process runner

Status: draft
Depends on: [02](02-repo-skeleton-and-conventions.md)

## Goal

A small, coroutine-native way to run external tools (ffmpeg, whisper-cli) from the backend: stream their output line by line, capture the exit code, kill them when the owning coroutine is cancelled, and freeze/unfreeze them with `SIGSTOP`/`SIGCONT` so a running transcription can be paused.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): all async work via Kotlin coroutines.
- [ADR 0004 — Chunked transcription with SIGSTOP pause](../docs/decisions/0004-chunked-transcription-sigstop-pause.md): pause = `SIGSTOP` the current whisper-cli process, resume = `SIGCONT`.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): external tools are invoked directly, never through Python.

## Proposal (pending approval)

- Coroutine wrapper around `ProcessBuilder`: `suspend fun run(cmd, onStderrLine)` that starts the process and returns its exit code (plus captured stderr for error messages).
- stdout and stderr are read as `Flow<String>` (one element per line) on `Dispatchers.IO`; both streams are drained concurrently so a full pipe buffer never blocks the child.
- Cancellation: when the calling coroutine is cancelled, the runner calls `Process.destroy()` (SIGTERM), waits a grace period, then `destroyForcibly()` (SIGKILL); `run` does not return until the process has exited.
- `suspend()` / `resume()` on a running process send `SIGSTOP` / `SIGCONT` by spawning coreutils `kill -s STOP <pid>` / `kill -s CONT <pid>` (Java has no signal API); the pid comes from `Process.pid()`.
- Cancelling a stopped process works too (the runner sends `SIGCONT` if needed so `SIGTERM` is delivered; to be confirmed by a test).
- No raw threads or executors; blocking reads/waits only inside `withContext(Dispatchers.IO)`.

## Open questions for the user

- Grace period between `destroy()` and `destroyForcibly()` (proposed: 5 s)?
- Spawn coreutils `kill`, or call `kill(2)` through JNA (no extra process, but a new dependency)?
- Should stdout be exposed as a `Flow` to callers, or only collected internally (whisper-cli writes its JSON to a file, ffmpeg PCM goes to a file or pipe)?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Running a command that exits successfully returns exit code 0 and its stdout lines in order
- [ ] Running a command that fails returns its non-zero exit code and its stderr lines
- [ ] Each stderr line is delivered to the `onStderrLine` callback as it is written
- [ ] Cancelling a running command (`sleep 100`) kills the process and the call returns promptly
- [ ] A process that ignores SIGTERM is killed forcibly after the grace period
- [ ] SIGSTOP freezes the output of a counting shell loop; SIGCONT resumes it from where it stopped
- [ ] Cancelling a stopped (SIGSTOP) process still terminates it
- [ ] Starting a non-existent executable fails with a clear error instead of hanging

These are integration tests with real processes (tagged `Integration`).

## Acceptance criteria

- Cancelling the coroutine running `sleep 100` leaves no `sleep` process behind (checked via the pid).
- A stopped counting loop produces no new output for a measurable interval and continues after `SIGCONT`.
- Exit code and stderr of a failing command are available to the caller.
- No `Thread`, `ExecutorService` or `CompletableFuture` in the runner code.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
