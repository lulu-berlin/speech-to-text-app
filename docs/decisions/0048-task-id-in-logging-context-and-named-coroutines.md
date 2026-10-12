# 0048 — The task's id in the logging context (MDC) and named engine coroutines

Status: accepted · Date: 2026-10-12

## Context
The engine's log lines named their task only in the message text (`"Task {}: …"`, the work-directory warnings), and some named none. Once lines interleave (one task's job, its whisper progress reader, file and JDBC work on `Dispatchers.IO`, embeddings on `Dispatchers.Default`, HTTP commands, the startup sweep), you cannot separate one task's lines without parsing messages. Coroutine dumps showed `coroutine#7` rather than which task a coroutine runs. A thread-local MDC alone does not work, because a task's coroutine resumes on whatever thread its dispatcher picks. Roadmap item 46 settled the approach (consultation 2026-10-12).

## Decision
- **MDC key `taskId`**, set for the whole task job: `ProcessingEngine.runInJob` runs the task inside `withContext(MDCContext(<caller's map> + taskId))` from `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j`, at the catalog's `kotlinx-coroutines` version. The library applies the map around every resumption on any dispatcher, and child coroutines (the progress reader, `withContext(Dispatchers.IO)` blocks) inherit it.
- **The thread's MDC is never written.** The idiom in the kotlinx docs (`MDC.put` on the thread, then `MDCContext()`) leaks. `MDCContext` saves the thread's current map as the state to restore, and that map already contains the seed, so on every suspension the launching thread kept the id. Untagged coroutines that ran there next (the worker, the event bridge) logged under the task's id. With more than one thread (`Dispatchers.Default`) the final `MDC.remove` ran on whichever thread the job finished on, so the id stayed on the original thread after the task had ended. Item 46's cycle 5 reproduced this and pins the fix.
- **Only the task job is tagged** (the command boundary). Pause, resume, cancel and delete run in the HTTP caller's coroutine and stay untagged; their messages name the task. The startup sweep, the device probe and an idle worker carry no `taskId` key at all, so the key is absent rather than blank.
- **Coroutine names**: each task job is launched with `CoroutineName("task-<id>")`, the worker with `CoroutineName("engine-worker")`. The coroutine debugger and dumps show these names.
- **Console**: `logback.xml` prints ` [task <id>]` after the logger name when the key is present and nothing extra when it is absent (`%replace( [task %X{taskId}]){' \[task \]', ''}`). Tests read the captured event's MDC map, not the console text.
- **No end-to-end test** (exemption from ADR 0046): this is observability only. Nothing the browser sees changes.

## Consequences
- One task's lines can be filtered by the `taskId` field, through suspensions and dispatcher switches. Code that a task calls needs no logging changes to be tagged.
- The id lives in a coroutine context element, never on a thread. A future logging change must not reintroduce `MDC.put` on a thread that runs coroutines.
- `kotlinx-coroutines-slf4j` becomes a direct dependency. It was already resolved transitively through Ktor at 1.11.0, so `backend/deps.json` stays unchanged (the user verifies `nix build .`).
- Request-scoped tagging of HTTP commands and tagging of subprocess output are out of scope. They need a new decision.
