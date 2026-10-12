# 46 — Contextual logging: the task's id in every log line (MDC) and named coroutines

Status: in-progress
Depends on: [11](../docs/specs/processing-engine.md) (done; the engine whose task job this tags)
Blocks: —

## Goal

While a task is being processed, every log line the engine or anything it calls emits carries that
task's id, so one task's logs can be separated from another's and from engine noise — including
after a suspension, across dispatcher threads. And the coroutine running a task is named after the
task, so the coroutine debugger and dumps show `task-<id>` instead of `coroutine-7`.

Today the id lives in the message text only (`"Task {}: …"`, `ProcessingEngine`'s
`fallBackToCpu`, the work-directory cleanup warns, …). That works when one thing logs at a time and
is lost the moment lines interleave. Contextual tagging makes the id a property of the log record,
searchable and filterable without parsing messages, and it survives the engine's real concurrency:
one worker, one task job, progress callbacks on reader coroutines, JDBC and file I/O on
`Dispatchers.IO`, embeddings on `Dispatchers.Default`.

## Proposal (starting point; to be settled in the consultation)

- **New direct dependency** `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` at the catalog's
  `kotlinx-coroutines` version (1.11.0): a `[libraries]` entry + one `implementation` line in
  `backend/build.gradle.kts`. The artifact is **already in the resolved graph** as a transitive of
  Ktor core (`backend/deps.json` contains `kotlinx-coroutines-slf4j/1.11.0`), so at the same
  version the Gradle→Nix lockfile should come out unchanged — the ADR 0042 precedent (a pin that
  equals the resolved version changes nothing). The user still re-verifies `nix build .`
  (ADR 0010/0014).
- **Per-task tagging in `runInJob`** (`ProcessingEngine`): the task's job launches with
  `CoroutineName("task-<id>")`, and the job body seeds the id into the MDC and runs the task inside
  `withContext(MDCContext())`. Mechanics (the canonical kotlinx pattern, docs "Debugging coroutines"
  → logging): the id is put into the MDC on the coroutine's thread first; `MDCContext`'s
  constructor captures that map at creation; the library re-applies it around every resumption,
  across dispatcher switches, and child coroutines inherit it. Result: logs from the chunk loop, the
  IO file/JDBC blocks, the embedding loop, and the whisper progress reader all carry the id — while
  whichever previous task's id was on the thread is restored afterwards, so nothing leaks.
- **Named worker**: the engine worker gets a fixed name (`engine-worker`), distinct from per-task
  names. Exact placement (the worker scope vs. the `launch { engine.start() }` site) is an
  implementation detail for the cycle; task-agnostic logs stay untagged either way.
- **Console visibility**: `logback.xml`'s pattern gains `%X{taskId:-}` (the id printed when
  present, nothing when absent). Cosmetic only: tests capture events off an appender and read the
  event's MDC map, so captured-log assertions are unaffected.
- **Out of scope**: route/request-scoped tagging (HTTP commands run in the caller's coroutine, not
  the task job — their messages already name the task; a per-client SSE id is a later concern),
  tagging whisper-cli/ffmpeg subprocess output (captured, not logged live), the frontend.

## Open questions (answered 2026-10-12)

- MDC key name → **`taskId`** (user).
- Failure-path reach → **accept the boundary**: only the task job is tagged; HTTP commands
  (pause/resume/cancel/delete) run in the caller's coroutine and stay untagged — their messages
  name the task (user).
- New ADR? → **Yes, ADR 0048** (user): the approach — task-id in the logging context via
  `kotlinx-coroutines-slf4j` + `CoroutineName("task-<id>")` per task job, key `taskId`, the named
  worker, and the command boundary. ADR 0046 exemption: observability-only — nothing the browser
  sees changes (state the reason in the ADR).

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-12 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user:

- [x] A log line emitted while a task is being processed carries the task's id in the log event's MDC map (captured with the existing `withCapturedLogs` harness; asserted on the event's MDC property map, not the message text)
- [x] The id survives suspension and dispatcher switches: a log from inside the embedding loop (`Dispatchers.Default`) and from a chunk's `withContext(Dispatchers.IO)` block carries it
- [x] A log emitted from the whisper progress reader (the `async(Dispatchers.IO)` reader coroutine's callback) carries it
- [ ] The task's own failure path (a failing chunk → task FAILED) logs with the id
- [ ] No leakage: after task A completes, a log emitted while task B runs — or while the worker is idle — carries B's id or none, never A's
- [ ] Task-agnostic logs (startup device probe, worker idle) carry no `taskId` key at all (absent, not blank)
- [ ] The coroutine running a task is named `task-<id>` (observed from inside a fake transcriber through the existing engine-test seam)
- [ ] Must not break: `gradle check` (ktlint + suites) and `gradle integrationTest` stay green; captured-log tests are unaffected by the console pattern change
- [ ] (user-verified) `nix build .` succeeds; `backend/deps.json` unchanged — the artifact already resolves at 1.11.0 (ADR 0042 precedent; regeneration only if the graph actually changed)

## Acceptance criteria

- In a real run, every engine log line between a task's RUNNING and its terminal status carries the
  task's id, visible in the console via the pattern and in the log record; a coroutine dump shows
  `task-<id>` for the task's coroutine.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-12: filed (draft) from the prep for the voize tech deep-dive — the concrete "what I'd add
  next" answer (correlate one task's logs across suspension; name coroutines so dumps show task
  ids). `backend/deps.json` already resolves `kotlinx-coroutines-slf4j/1.11.0` (transitive via
  Ktor), so no lockfile change is expected (verified 2026-10-12).
- 2026-10-12: consultation answers: key `taskId`; the command boundary accepted; ADR 0048 to be written. Test list (9 items) approved; status stays `in-progress` in the worktree.
- 2026-10-12 (workspace): cycle 1 green (the core MDC map; drove the dependency + `withTaskLoggingContext`); refactor approved (the `TASK_ID_MDC_KEY` constant). Cycle 2 green on arrival (regression pin — the cycle-1 implementation already covers the dispatchers; the fake seams log from inside `Dispatchers.IO`/`Dispatchers.Default`); no refactor suggested. Cycle 3 green on arrival (regression pin: child coroutines inherit the context — the fake's progress comes from a `coroutineScope`+`async(Dispatchers.IO)` reader with the log + a `progressReaderLogged` flag); no refactor suggested.
