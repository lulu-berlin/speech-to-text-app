# 96 — Cleanup from the 2026-11-06 audit: dead code, duplicated code and comments that contradict it

Status: spec-approved
Depends on: [95](../docs/specs/failure-paths.md) (same engine and route files), [67](67-tui-frontend.md) (the `client/stt-client` rows only)
Blocks: —

## Goal

The code carries no unused production code, no second copy of a helper, constant or field list
that can drift from the first, and no comment that contradicts the code. Two convention deviations
are fixed or decided. The app's behaviour does not change.

## Evidence

The audit read `main` at `06c304f1`. The root agent had every finding re-checked against the code,
which corrected some counts and paths; the corrected findings are below. Paths are from
`backend/src/main/kotlin/stt/` or `frontend/src/` unless shown in full.

### Dead code

- **The queue aggregate is unused.** It is `queue/TaskQueue.kt` plus seven
  outcome/rejection/entry files (`QueueEntry`, `QueueCreationOutcome`, `QueueCreationRejection`,
  `QueueTransitionOutcome`, `QueueTransitionRejection`, `ReorderOutcome`, `ReorderRejection`).
  - Only `TaskQueueSpec` and `TestQueue` use them.
  - KDocs say reorder goes through it: `db/TaskRepository.kt:225-229` and
    `engine/ProcessingEngine.kt:218-220`.
  - Item 04 introduced it (`docs/specs/queue-domain-model.md`).
  - Item 95 moves its duplicate-id check into the reorder route.
- **Test-only repository methods.** No production code calls:
  - `TaskRepository`'s `createFromUploadOrDuplicate`, `commitOrDuplicate`
    (`db/TaskRepository.kt:60-72`), `insert` (`:29`), `list` (`:128`), `setStatus` (`:242`) and
    `createFromUpload` (`:38`);
  - `AudioRepository.metadata` (`:18`) and `SegmentRepository.insertAll` (`:36`).

  `TaskRepository.kt:494-496` still says every task is created through `createFromUpload`;
  production uses `createFromStaged` (`http/UploadRoutes.kt:137`).
- **`Task.contentType` and `Task.model` are written but never read.**
  - Defined at `db/Task.kt:8,19`, written at `http/UploadRequest.kt:85,96`.
  - Neither `TaskJson` nor the frontend reads them.
  - ADR 0024:31 calls `content_type` "for display only", yet nothing displays it.
- **Production code that only tests call.**
  - `transcribe/ParseSegments.kt:25` `parseSegments`; production uses `parseWhisperOutput`.
  - `Main.kt:172`: a `probed` default no caller uses.
  - `SpeakerModel.sessionOptions`'s `null` default (`:30`).
  - `SpeakerModelMetadata.sampleRate` and `featureNormalizeType` (`:12,14`): parsed but never read.
  - `Card` and `Skeleton` (`shared/ui`): kept alive only by their own tests and the reduced-motion
    scan in `App.test.tsx`.
- **Unneeded exports.** `e2e/lib/ui-dist.ts:75,87` exports `digestPath` and `recordUiDigest`,
  which only the module itself uses.
- **An unneeded Rust dependency.** The direct `muda` dependency
  (`client/stt-client/Cargo.toml:19,33`) is never imported; `tray-icon` 0.26.1 already brings
  `muda` 0.21.2 (`client/Cargo.lock`).
- **Merged from `docs/known-issues.md`** (Frontend): `listTasks` and `getTask` are used only by
  tests (`shared/api/client.ts`). That row's "`taskDiarizing` nowhere" is stale:
  `QueueView.test.tsx` uses it. The row's suggested guard: the frontend's and e2e's `tsconfig`
  leave `noUnusedLocals` and `noUnusedParameters` off, so `tsc` never flags a dead local. #108
  removed the current ones, and `main` now passes `tsc` with both flags on.

### Duplication

- **Backend wiring.**
  - `engine/EngineModule.kt:54-70` builds six repositories by hand; `http/SttApp.kt:65-72` already
    registers four of them.
  - `:50` builds a second `SegmentRepository` for the diarizer.
  - This bypasses ADR 0013 ("the object graph is declared in one place").
  - ADR 0039 describes a resolution mechanism the code no longer uses.
- **Every settings field is spelled out in eight places.**
  - Backend: `SettingsJson`, `SettingsEventJson`, `SettingsRequest` and the two mappings in
    `http/SettingsRoutes.kt` (`:28-43`, `:47-57`, `:61-72`, `:201-223`, `:226-240`).
  - Frontend: three per-field switches or if-chains in `features/settings/SettingsView.tsx`
    (`:221-258`, `:262-291`, `:351-386`).
- **Backend copies.**
  - Tree deletion three times: `db/migration/MigrationFiles.kt:11-14`,
    `engine/ProcessingEngine.kt:936-943`, `db/ScratchDir.kt:43-53`. Item 95 adds one
    best-effort helper; this item folds the copies into it.
  - The clip window's second math twice: `engine/ClipPcmSource.kt:50-56` and
    `engine/ProcessingEngine.kt:965-987`.
  - 16 kHz in four places: `audio/Pcm.kt:5`, `diarization/Fbank.kt:8`,
    `diarization/SpeakerModelMetadata.kt:3`, `diarization/EmbeddingWindows.kt:54`.
  - The 4000-sample frame twice: `audio/AudioChunker.kt:46`, `audio/FlacSplitter.kt:24`.
  - The SRT and VTT cue body twice: `outputs/RenderSrt.kt:15-19`, `outputs/RenderVtt.kt:21-25`.
  - Task lookup → 404 written in several routes.
  - `pendingStatuses` restates the complement of `terminalStatuses`.
- **Backend tests.**
  - The two diverged writer-lock race helpers (`db/ConcurrencySpec.kt:180-217`, decided by an
    exact call count, vs `engine/TestEngine.kt:409-557`, writer-keyed) moved to item 94 at the
    user's direction (2026-11-06).
  - `pumpUntil(200.milliseconds) { false }` used as a sleep or "nothing happened" proof in eight
    places, against the virtual-time convention (AGENTS.md § Testing,
    `docs/conventions/backend.md:60`): `ProcessingEngineDiarizationSpec.kt:110,259`,
    `ProcessingEngineEventsSpec.kt:94`, `ProcessingEngineRestartSpec.kt:143,183`,
    `ProcessingEngineTaskPauseSpec.kt:206,273,308`.
  - Five hand-written settings PUT bodies (`SettingsUpdateSpec`, `SettingsDefaultFrontendSpec`,
    `SettingsTerminalCommandSpec`, `StaleDefaultFrontendSpec`, `SettingsFallbackSpec`).
  - `SegmentRevertSpec.kt:109-150` re-declares the turns and edit helpers.
  - `stt.http.testConfig` shadows `stt.config.testConfig`, so 52 test files spell the fully
    qualified name.
- **Frontend.**
  - `errorMessage` exists twice, identical: `shared/api/types.ts:231-233` and
    `shared/errorMessage.ts:3-5`. Imports are split 3/4. The known-issues row says the move was
    done.
  - The three speakers-event handlers are copy-pasted: `shared/api/client.ts:311-325` and
    `shared/state/connectionMachine.ts:128-153`.
  - Status sets are re-declared in `HistoryView.tsx:16,19-24` and `TaskTranscriptView.tsx:47,50`.
  - The speaker-mode list exists twice: `SettingsView.tsx:27`, `UploadOptionsPanel.tsx:20`.
  - `TaskTranscriptView.tsx:598-611`'s `focusin` effect has no dependency array, and the click
    effect (`:576-592`) depends on the whole playback state. Listeners churn on every render; no
    user-visible defect.
  - **Merged from `docs/known-issues.md`** (Frontend): `clampCount` in `UploadOptionsPanel.tsx:30`,
    and the actor `.stop()/.start()` restart idiom written three times (`TaskRow.tsx`,
    `HistoryRow.tsx`, `TaskTranscriptView.tsx`).
- **e2e.**
  - `e2e/lib/backend.ts:748-768` `sttAppPath()` runs `cargo build` on every call, though its doc
    says "built once per run". Callers: `:779`, `:793`, `e2e/tests/helpers/service.ts:34`.
  - Frontend registration exists twice: `backend.ts:789-798` and `tests/helpers/service.ts:47-56`.
  - The theme-toggle loop is written four times: specs 15, 16, 52 and 59.
  - `desktop-tests/helpers/app.ts:213-316` re-implements `lib/processes.ts` and hand-rolls
    `waitUntil`.
  - The desktop helper `transcribeAndOpenTranscript` (`desktop-tests/helpers/app.ts:155-164`)
    uploads its clip but asserts `jfk.wav` and waits on a fixed 300 s clock, the pattern item 84
    removed from the browser journey.
- **Client and desktop.**
  - The registry read exists twice: `client/stt-client/src/lib.rs:927-950` and
    `src/bin/stt-app.rs:213-235`. The library's warning says `stt-tray:` (`lib.rs:933`).
  - The client tests hand-write the same descriptor JSON seven times, with two near-identical
    `stand_in()`s (`tests/cli_default.rs:9`, `tests/cli_open.rs:8`).
  - The desktop app has three per-platform directory switches: `desktop/src/backend.ts:41-51,
    266-275`, `desktop/src/models.ts:37-47`.
  - Item 67 changes `client/` on its own branch.

### Comments that contradict the code

- `db/TaskRepository.kt:451` and `engine/ProcessingEngine.kt:287` name the `chunk_audio` table,
  which V11 dropped.
- `db/SettingsRepository.kt:11` says the row is written whole, but `fallBackToCpu` and
  `dismissDeviceFallback` write single columns.
- `client/stt-client/src/lib.rs:886-889`: a doc block sits on `register_descriptor` but describes
  `read_registry`.
- `client/stt-client/src/lib.rs:266` names `HEALTH_TIMEOUT` where `ensure_service` uses
  `START_TIMEOUT`.
- `features/queue/QueueView.tsx:43-44` describes a constant that no longer exists.
- `.dockerignore:2` names `nix build .#stt-app`, an attribute that does not exist.
- `nix/ffmpeg.nix:7`'s example names 8.1.2; the assets are 8.1.3.
- `backend/src/main/resources/db/migration/V13__segment_original_text.sql:1` cites ADR 0060 where
  ADR 0061 is meant.

### Convention deviations

- **A blocking classpath read on a request thread.** `http/UploadRequest.kt:125-160` reads the
  extensions and languages lists with `contextClassLoader…readText()` in top-level `val`s, which
  first run on the first upload's request thread, outside `Dispatchers.IO`. A missing resource is
  then a `!!` at the first upload, not at boot.
- **Environment variables read outside `Config`.** `service/ServiceDirs.kt:47-60` reads
  `STT_CONFIG_DIR` and `STT_RUNTIME_DIR` directly. `docs/conventions/backend.md:24` says `STT_`
  variables are read in exactly one place, no ADR permits a second, and
  `docs/check-architecture-names.ts` therefore cannot let `docs/architecture.md` name them.

## Proposal

1. **Delete** the dead code above, and fix the KDocs and docs that present it as live
   (`docs/architecture.md`, `docs/backend-reimplementation.md` for `TaskQueue`). **Drop
   `contentType` and `model` with a migration.**
2. **Fold each duplicate into one definition**, the existing shared module where one exists
   (`shared/errorMessage.ts`, `shared/api/types.ts`'s status sets, `e2e/lib/processes.ts`,
   `lib/wait.ts`):
   - The engine's repositories come from the application's DI registrations.
   - The settings fields get one field list per side, which the per-field code is built from.
   - The test helpers live once.
   - `sttAppPath()` builds once per run.
   - The desktop helper waits on the task's state, item 84's pattern.
3. **The real-clock waits** become virtual-time waits, or event-keyed waits where a "nothing
   happened" claim needs one, as items 47 and 58 did.
4. **Fix the comments.**
5. **Conventions**:
   - The classpath lists load once at wiring, on `Dispatchers.IO`.
   - `STT_CONFIG_DIR` and `STT_RUNTIME_DIR` move into `Config`, and `ServiceDirs` takes them as
     parameters.
6. **Turn the unused-locals guard on**: `noUnusedLocals` and `noUnusedParameters` in the frontend
   and e2e `tsconfig`s.
7. **Take the three named backlog rows**: the two `segment(...)` test builders, `TestTransitions`'
   copy of `allowedTransitions`, and turn rows keyed by array index.

## Open questions

None. (Settled 2026-11-09, see "Decided".)

## Decided (consultation, 2026-11-09)

- **`contentType` and `model` are dropped**, with a migration; no view or client wants them.
- **Item 75's two guards stay**: the source-comment scan and the tree/live-document check cover
  disjoint things.
- **`STT_CONFIG_DIR` / `STT_RUNTIME_DIR` move into `Config`**; `ServiceDirs` takes them as parameters
  and `docs/architecture.md` can name them.
- **The unused-locals guard is on**: `noUnusedLocals` and `noUnusedParameters` in the frontend and
  e2e `tsconfig`s, so a dead local fails `pnpm build` and the type checks.
- **The settings fields get one list per side**, which the per-field code derives from; not a
  generated shared contract.
- **Three backlog rows join the item**: the two `segment(...)` test builders, `TestTransitions.kt`'s
  copy of `allowedTransitions`, and turn rows keyed by array index.
- **The `client/stt-client` rows stay in this item**; if item 67 is still running when the rest is
  done, they split into a small follow-up.

## Test list

Draft, to settle with the user. Mostly a refactor item: the existing suites are its main proof,
and each line below names the behaviour that must hold after a removal or fold.

- [ ] (must not break) After the dead code is removed, `gradle check`, `gradle integrationTest`,
      `pnpm test`, the docs checks, `cargo test`, `cargo clippy` and the e2e journeys stay green.
- [ ] (basic) `contentType` and `model` are gone: a database at the previous version migrates, its
      tasks keep every other field, and their JSON is unchanged.
- [ ] (basic) A settings field is declared once per side: adding a field to the list is enough to
      carry it through GET, PUT, the event and the Settings page's save, revert and recommit
      (proven with a test-only field or the existing fields' round trips).
- [ ] (basic) The engine resolves its repositories from the application's DI registrations; the
      diarizer and the engine share one `SegmentRepository`.
- [ ] (basic) The eight real-clock waits are gone: the specs' "nothing happened" claims hold on
      virtual time or an event, and the specs stay green.
- [ ] (basic) `sttAppPath()` runs `cargo build` at most once per e2e run.
- [ ] (basic) The desktop transcribe helper asserts the clip it uploaded and ends when the task
      does, not on a 300 s clock.
- [ ] (basic) The classpath lists are loaded at wiring on `Dispatchers.IO`, and a missing list
      fails the service's start rather than the first upload.
- [ ] (basic) `STT_CONFIG_DIR` and `STT_RUNTIME_DIR` are read by `Config` only, and the
      service-dirs case table (`docs/service-dirs.json`) still holds for Kotlin and Rust.
- [ ] (basic) A dead local in the frontend or e2e sources fails the type check (proven with one
      planted, then removed), and the trees pass with both flags on.
- [ ] (must not break) The client's registry is read by one function; `stt-app` and the tray print
      their own warning prefix.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: a refactor-only item, which ADR 0046 exempts. The
e2e journeys running unchanged are part of its must-not-break proof.

## Approval log

- 2026-11-06: filed as a draft by the root agent at the user's request, from the 2026-11-06 code
  audit after a verification pass. The user chose one item for all of the audit's cleanup. The
  known-issues rows "Dead exports" and "Duplicated helpers" (Frontend) were merged into it.
- 2026-11-09: consultation (user): drop `contentType`/`model` with a migration; keep both of item
  75's guards; move the two `STT_` variables into `Config`; turn the unused-locals flags on; one
  settings list per side; absorb the three named backlog rows; the client rows stay here and split
  into a follow-up if item 67 is still running. Status stays `draft` until the updated test list is
  approved.
- 2026-11-09: approved by the user: the decisions recorded above and the 11-line test list as
  updated. Status set to spec-approved.
