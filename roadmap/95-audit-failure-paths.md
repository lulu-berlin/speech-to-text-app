# 95 — Failure paths that stop work or hide the error: the worker, the service, the API and the frontend's requests

Status: draft
Depends on: [11](../docs/specs/processing-engine.md), [42](../docs/specs/worker-stability.md), [12](../docs/specs/http-api-and-sse.md), [41](../docs/specs/upload-hardening.md), [32](../docs/specs/settings-page.md), [66](../docs/specs/local-service-and-frontends.md), [73](../docs/specs/speaker-reassignment.md), [87](../docs/specs/writes-and-the-connection.md) (all done)
Blocks: [96](96-audit-cleanup.md) (the cleanup edits the same engine and route files)

## Goal

Every bug the 2026-11-06 code audit found is fixed behind a failing test:
- a startup, cancel or cleanup failure never stops the queue or replaces a run's real error;
- the diarizer never reuses a closed model;
- the service can always be quit, even after it fell back from its durable port;
- the HTTP API answers what its contract says instead of a 500 or a silent accept;
- the frontend's requests follow item 87's error and write-gate rules.

## Evidence

The audit read `main` at `06c304f1`. The root agent had every finding re-checked against the code
by read-only passes, which corrected some of the audit's claims; the corrected verdicts are below
(all paths from `backend/src/main/kotlin/stt/` unless shown in full).

### The worker and its cleanup

- **HIGH — a startup error kills the only worker for the process's life.**
  - `engine/ProcessingEngine.kt:162-168`: `start()` launches
    `{ tasks.recoverRunning(); scratchDir.clear(); workerLoop() }`.
  - `db/ScratchDir.kt:43-55`: `clear()` says "best-effort" but has no `try`.
  - Nothing catches or restarts it: no `CoroutineExceptionHandler` in `main`, `start()` runs once
    (`engine/EngineModule.kt:85`), and the scope is a `SupervisorJob` child.
  - The user's view: one filesystem error at startup, and the app serves, accepts uploads and shows
    QUEUED tasks that never run, with no status, event or error.
  - Item 42 covered only the lookup loop (`docs/specs/worker-stability.md:63`).
- **MED — cancelling a started, paused task after a restart leaves the queue waiting.**
  - `engine/ProcessingEngine.kt:418-435`: `cancelTask` never calls `wake()`. The other six commands
    do (`:188`, `:216`, `:226`, `:279`, `:303`, `:405`), and no timer wakes the worker.
  - After a restart, `nextTask()` returns null while a started PAUSED task exists (`:492-503`,
    `db/TaskRepository.kt:170-181`), so the worker blocks on `wakeups.receive()` (`:507-511`).
  - Cancelling that task stores CANCELED, but every QUEUED task behind it waits for an unrelated
    upload, reorder, resume, retry or delete.
  - `ProcessingEngineRestartSpec.kt:104-107` covers resume across a restart, but not cancel.
- **MED — a failed cleanup replaces the run's real error.** Kotlin gives a throwing `finally`
  precedence, so the delete's exception becomes the task's error and the real cause is never
  logged. Four sites:
  - `engine/ProcessingEngine.kt:731-736`: the chunker's scratch directory and source.
  - `:755-757`: `writeOriginal`'s partial file.
  - `:984-986`: a chunk's window.
  - `engine/ClipPcmSource.kt:59-64`: the diarization clip.

  Two consequences:
  - A process interruption that should requeue the task (item 42: a SIGTERM surfaces as an
    in-flight `CancellationException`, `process/ProcessRunner.kt:107`) skips the
    `catch (CancellationException)` at `:636` and ends **FAILED instead of QUEUED** when a cleanup
    throws.
  - Otherwise the task fails with `AccessDeniedException: …/chunking` instead of the real error.

  The audit's "a user cancel can end FAILED" is wrong: in a cancelled job the FAILED write never
  runs.
- **MED — the same pattern in uploads turns a 413 into a 500.** `db/StagedUpload.kt:43-46,62-66`:
  a failed `deleteUnclaimedOriginal` replaces `UploadTooLargeException`, so `UploadRoutes.kt:96-100`
  never sees it.
- **LOW — `removeScratch` swallows cancellation.** `engine/ProcessingEngine.kt:951-957` has
  `catch (e: Exception)` with a log. `docs/check-exceptions.ts` accepts it because it logs the
  throwable.
- **LOW — `running` can stay set to a finished job.**
  `engine/ProcessingEngine.kt:581-583`: `finally { runningLock.withLock { running = null } }` is a
  suspending `finally`; on a cancelled job with a contended lock it throws and leaves `running`
  set.
- **LOW — a cancel leaks the chunker's file channel.** `audio/AudioChunker.kt:33-35`:
  `finally { withContext(Dispatchers.IO) { channel.close() } }` has no `NonCancellable`, so a
  cancel skips `close()` and leaks the channel. The audit's "reads run on Default" is wrong today:
  the only caller consumes the rows inside a write transaction on `Dispatchers.IO`.

### Models and the service

- **MED — the diarizer can keep a closed model.**
  - `diarization/SpeakerDiarizer.kt:58-68` closes the old model before loading the new one. If the
    load throws (a thread-count change, an unreadable model), `model`, `embeddings` and
    `loadedThreads` still describe the closed session.
  - The next task with the old thread count takes the cached branch and fails at DIARIZING after
    its transcription was stored, and so does every such task after it.
  - `close()` (`:70-72`) skips `loadLock` and has no production caller: `EngineModule.kt:50`
    registers no cleanup, so the KDoc's "released when the application stops" is false.
- **MED — after a durable-port fallback the service cannot be quit.**
  - `service/ServiceServer.kt:26` stops the first server on `BindException`, which raises
    `ApplicationStopPreparing` (Ktor 3.6.0, Netty `stop`).
  - `Main.kt:130-133` cancels `serviceScope` on that event. `serviceScope` holds the quit watcher
    (`:97-100`) and the tray supervision (`:101-106`), so the fallback server runs without both.
  - The user's view: `POST /api/quit` answers 202, `stt-app quit` prints "the service is
    stopping", and the tray's Quit exits the tray, yet the service keeps running with no supervisor
    (ADR 0064's both-up-or-both-down is off). Only SIGTERM stops it.
  - The audit's "no processing" is wrong: the fallback server starts its own engine.
  - CI cannot see it: `PortFallbackIntegrationSpec` force-kills the process, and
    `QuitIntegrationSpec` binds a free port.
- **LOW — an unreadable `config.json` stops the service from starting.**
  - `service/ServiceInstance.kt:164-177` catches only `SerializationException`, so an IOException
    escapes `claim` (`Main.kt:64-65`) and startup fails.
  - That contradicts item 66's ticked line: "a `config.json` the service cannot read … is replaced"
    (`docs/specs/local-service-and-frontends.md:262-264`).
  - A hand-edited `{"port": 70000}` decodes, then `InetSocketAddress` throws out of `portToServe`
    (`:87-95,110-114`).
- **LOW — embedding windows go wrong on audio longer than about 18.6 h.**
  - `diarization/EmbeddingWindows.kt:45`: `(t0 + t1) / 2` in `Int` overflows on audio longer than
    about 18.6 h. The window then becomes the segment's first 20 s instead of its centre: a wrong
    embedding, no crash.
  - `:57`: `(ms * 16).toInt()` wraps at about 37.3 h.
  - No duration limit exists; only `STT_MAX_UPLOAD_BYTES` (1 GiB by default).

### The HTTP API against its contract

- **MED — moving segments to a new speaker answers 500 on a task without speakers.**
  - `db/SegmentRepository.kt:315`: `selectSpeakers(taskId).maxOf { … } + 1` throws on an empty
    list.
  - A COMPLETED task with speakers off has no speaker rows (`engine/ProcessingEngine.kt:872`), and
    `POST /api/tasks/{id}/speakers/reassign` with `"speakerId": null` checks only COMPLETED
    (`http/SpeakerRoutes.kt:95-98`).
  - `http/InternalError.kt` answers 500. The browser never offers this request
    (`TaskTranscriptView.tsx:862-864`); the API and the TUI client can send it.
- **MED — an upload's file part may have any name.**
  - `http/UploadRoutes.kt:81-91` takes any `FileItem`, though the contract requires `file`
    (`docs/specs/http-api-and-sse.md:22-23`, `docs/api/openapi.yaml:50-57`) and the rejection
    message says "No file part named \"file\"".
  - A file part named `language` is ignored rather than rejected (`:104-115`).
  - #108 has since changed this loop's part release; the line numbers here are from `06c304f1`.
- **LOW — the queue reorder accepts duplicate ids.**
  - `http/QueueRoutes.kt:55` compares sets, so `[1,2,2,3]` passes. The positions become 0, 2, 3:
    the order is right, but the gap persists.
  - The contract says "exactly the non-terminal ids, each once" (`docs/api/openapi.yaml:190`).
  - The 409 message re-reads the pending ids (`:58`), so in a race it can name a different set.
  - The only duplicate check lives in the unused `queue/TaskQueue.reorder` (item 96 removes that
    aggregate).
- **LOW — a deleted task can break the event stream or answer a succeeded command with 500.**
  - `http/EventsRoutes.kt:174`: `tasks.details(task.id)!!` throws when a buffered `task-updated`
    for a pending task is delivered after its delete. The stream ends, the browser reconnects
    once, and the service logs an ERROR.
  - The same `!!` after a successful command or upload (`http/TaskCommandRoutes.kt:79`,
    `http/UploadRoutes.kt:145`) answers 500 for an operation that took effect.
- **LOW — an unknown-kind frontend descriptor is skipped without a log line.**
  `http/FrontendsRoutes.kt:47-49,69` skips it silently; item 66 requires "skipped with a logged
  warning" (`docs/specs/local-service-and-frontends.md:141-142,218-219`).

### The frontend's requests (item 87)

- **MED — two requests bypass the client's error boundary.**
  - `shared/api/client.ts:86-89` (`fetchFrontends`) and `:164-176` (`reassignSegments`) call
    `fetch` directly.
  - During an outage, the reassign bar prints the browser's "Failed to fetch"
    (`speakerReassignMachine.ts:75-78`, `TaskTranscriptView.tsx:945-947`), against item 87's rule
    that no view prints the browser's transport text
    (`docs/specs/frontend-state-and-errors.md:166-176`).
- **MED — Settings hides a failed load of the frontends list.**
  - `features/settings/SettingsView.tsx:157-159`: `.catch(() => setFrontends([]))` swallows every
    failure, including the abort on unmount. The select then silently offers only "None".
  - This is against `docs/conventions/frontend.md:47` ("never swallow errors silently").
- **MED — two settings fields can be edited while the connection is down.**
  - "Default frontend" (`SettingsView.tsx:478`) and "Terminal command" (`:499`) use
    `disabled={inert || dismissing}` without the write gate, unlike the other six fields.
  - Item 87 requires every write control to be gated
    (`docs/specs/writes-and-the-connection.md:80`). `app/writeGate.test.tsx:177-183` omits both
    fields, so the gap is untested.

## Proposal

1. **Worker start**: recovery and the scratch clear run so that a failure is logged and the worker
   loop still starts. If recovery itself cannot run, the failure is logged and made visible (open
   question); it never silently ends the worker.
2. **Cancel wakes the worker** like the other commands.
3. **One best-effort delete helper** for every cleanup on the run and upload paths. It logs an
   IOException with the path, rethrows cancellation, and never replaces the exception in flight.
   `ScratchDir.clear` uses it. `removeScratch` rethrows cancellation. The `running` reset cannot
   leave a finished job behind. The chunker's channel close runs under `NonCancellable`.
4. **The diarizer** loads the new model before closing the old one, or clears its cache when the
   load fails. `close()` takes `loadLock`, and the DI cleanup closes the model when the
   application stops.
5. **The quit watcher and tray supervision** belong to the server that actually serves: the
   fallback server keeps them, and stopping the failed first server cancels nothing that the
   fallback needs.
6. **`config.json`**: an unreadable file (IOException) or an out-of-range port is replaced like a
   malformed one, as item 66 already states.
7. **`EmbeddingWindows`** computes in `Long`.
8. **The API**:
   - Reassign to a new speaker on a task with no speaker rows answers what the spec decides (open
     question).
   - The upload's file part must be named `file`, and a file part under a value field's name is
     rejected.
   - Reorder rejects duplicate ids with 409 and reads the pending ids once.
   - The events route skips a frame whose task is gone; `task-deleted` already tells the client.
   - The two command and upload responses answer from the state they wrote, not a re-read with
     `!!`.
   - An unknown-kind frontend descriptor is logged.
9. **The frontend**:
   - `fetchFrontends` and `reassignSegments` go through `request()`.
   - Settings shows the frontends load failure in the app's words and ignores the abort on unmount.
   - "Default frontend" and "Terminal command" take the write gate.

## Open questions

- **Reassign to a new speaker on a task without speakers**: 409 with a reason (a task transcribed
  without speakers has none to move between), or create speaker 0 and accept? The first keeps
  "speakers off" meaning no speakers.
- **Worker start when recovery fails** (for example, the database is unreadable): fail the service's
  startup loudly, or start the loop and retry recovery on the next wake? Failing startup is the
  boring choice: the service is unusable either way.
- **e2e (ADR 0046)**: the app's behaviour changes in visible places (the write gate on two Settings
  fields, the reassign error text, quit after a port fallback). Proposed: one e2e test for the two
  gated Settings fields with the service frozen (the item 87 journey's pattern, skipped on Windows
  like it), and the rest proven at the route and engine level. Or name an exemption.

## Test list

Draft, to settle with the user.

- [ ] (basic) A started task paused across a restart is canceled, and the QUEUED task behind it
      starts without any other command.
- [ ] (edge) A scratch clear that throws at startup is logged, and the worker still runs the next
      queued task.
- [ ] (failure) A chunker, chunk-window or diarization-clip cleanup that throws does not replace
      the run's error: the task fails with the original error, and the delete failure is logged.
- [ ] (failure) A process interruption whose cleanup then throws still requeues the task (QUEUED,
      not FAILED).
- [ ] (failure) An oversized upload whose staged original cannot be deleted still answers 413.
- [ ] (edge) A cancelled run's chunker channel is closed.
- [ ] (failure) A speaker-model load that fails after a thread-count change does not leave a
      closed model in use: the next task at the old thread count diarizes.
- [ ] (failure) With the durable port taken, the fallback service still stops on `POST
      /api/quit`, and its tray supervision still restarts a dead tray.
- [ ] (edge) An unreadable `config.json` and one with an out-of-range port are replaced at start,
      with the default port and a new secret.
- [ ] (edge) An embedding window on a segment past 18.6 h of audio is centred on the segment.
- [ ] (failure) Reassigning segments to a new speaker on a task with no speakers answers the
      decided status, not 500.
- [ ] (failure) An upload whose file part is not named `file` is rejected with 400
      `missing-file`; a file part under a value field's name is rejected.
- [ ] (failure) A reorder naming a pending id twice is rejected with 409, and the stored order is
      unchanged.
- [ ] (edge) A `task-updated` delivered after its task was deleted is skipped: the stream stays
      open, and `task-deleted` follows.
- [ ] (edge) An unknown-kind frontend descriptor is skipped with a logged warning.
- [ ] (failure) With the service unreachable, moving segments shows "Not saved: can't reach the
      app's service. Reconnecting…", not the browser's text.
- [ ] (failure) With the service unreachable, Settings says the frontends list could not be
      loaded instead of offering only "None"; leaving the page during the load shows no error.
- [ ] (ui) With the connection not live, "Default frontend" and "Terminal command" are disabled
      with the reconnecting title, like the other settings fields.
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test` and the e2e journeys
      stay green.

## e2e stance

To be settled (see Open questions). Proposed: one e2e test for the two gated Settings fields.

## Approval log

- 2026-11-06: filed as a draft by the root agent at the user's request, from the 2026-11-06 code
  audit after a verification pass. The user chose one item for all of the audit's bugs.
