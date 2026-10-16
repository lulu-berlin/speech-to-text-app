# Known issues (living backlog)

The open, unfixed defects and findings in the app. This document is the constant queue of known
problems; it never goes away and it is not a roadmap item.

## Maintenance rules

- **Add** an issue here as soon as a review, a test failure, or a user report finds a real problem —
  with severity and enough evidence (`file:line`) to re-find it.
- **Remove** an issue the moment a roadmap item is created that handles it: from then on it is
  *planned work* tracked by that item, and this list stays only the *unplanned* backlog. Do not keep
  a "handled by" column or an archive here.
- Severity guide: `HIGH` corrupts task state, exhausts resources, or hides failures from the user;
  `MED` is a real defect with a narrower trigger; `LOW` is cleanup, drift, or a decision to make.
- Rows marked **decision needed** are open questions, not code fixes; resolve them when (or instead
  of) touching the area.

Rules for the roadmap: issues currently planned by roadmap items are deliberately absent here —
[41 upload hardening](../roadmap/41-upload-hardening.md) (size cap, multi-part spool overwrite,
duplicate-race 500), [42 worker stability](../roadmap/42-worker-stability.md) (task setup outside the
guarded try).

## Backend runtime

| Issue | Sev | Evidence | Notes |
| ----- | --- | -------- | ----- |
| Cancel/pause/delete race a task's start: `running` is registered **after** `scope.launch` in `runInJob`, so a command in that window skips `cancelAndJoin()` (process not killed) yet its conditional write lands — and `runTask`'s unconditional `setCompleted` overwrites CANCELED/PAUSED with COMPLETED | HIGH | `backend/src/main/kotlin/stt/engine/ProcessingEngine.kt:443-456` vs `:346`, `:202-210`, `:252`; `db/TaskRepository.kt:336-341` | Fix: register inside `runningLock` before launch, or tear down against the persisted status |
| Task-failure path never logs the throwable — only `e.message` is persisted; and when `failIfRunning` loses the race to a cancel, nothing is recorded or logged at all | MED | `ProcessingEngine.kt:501-506` | Log with the item-46 MDC context in addition to the DB write |
| Upload acceptance is the client-supplied filename extension only; content type is read and ignored, no magic-byte sniffing — any bytes renamed `x.mp3` pass `201` and fail later at ffmpeg decode | MED | `backend/src/main/kotlin/stt/http/UploadRequest.kt:44-58` | Content validity only; size cap / multi-part / 409 are item 41 |
| Hottest queries scan without an index: `tasks.status` and `tasks.queue_position` unindexed (`TasksTable`: PK only); `nextQueued`, `pendingOrderedIds`, `startedPaused`, `nextQueuePosition`, `retryIfPossible` all filter/sort on them | MED | `backend/src/main/resources/db/migration/V1__init.sql`; `db/TaskRepository.kt:102-151` | Add indexes in a new migration |
| `listDetails` runs two extra queries per task (`audio_header` exists, `segments` max end) in a read transaction — O(N) per list / snapshot | LOW | `db/TaskRepository.kt:428-447` | One grouped query, or materialize duration |
| CHUNKING holds the whole canonical clip in heap two to three times: the encoded FLAC is read whole (`Files.readAllBytes`), the splitter copies the header and every frame out of it, and `toCanonicalRows` joins each second's frames into another array — a 90 min clip is several hundred MB transient, while serving and diarization stream one row at a time | LOW | `audio/AudioChunker.kt:72`; `audio/FlacSplitter.kt:99,107`; `audio/FlacRows.kt:36` via `engine/ProcessingEngine.kt:537` | Split from a file channel, or build the one-second rows during the frame walk; found in item 44's review 2026-10-15, not fixed there. Measured 2026-10-16 (item 43's long-clip run, heap after GC): a 20 min 16 kHz mono clip reaches 100 MB right after upload (25.8 s JVM uptime), and a 1200 s fixture reproduced 110 MB in the sandbox — the same run's diarization phase stayed at 20–23 MB |
| Stored client-supplied `originalFilename` embedded raw into `Content-Disposition: attachment; filename="…"` — a name with `"`/CR/LF breaks or injects header lines | MED | `outputs/DownloadFileName.kt:22-29`; `http/TranscriptRoutes.kt:82` | Sanitize at upload or RFC 5987 `filename*` |
| `STT_DEVICE` / `STT_THREADS` silently ignored (superseded by the settings table, item 32) | LOW | `config/Config.kt:25-62`; `config/Device.kt` | **Decision needed**: document as retired, or fail fast |
| Diarized `.txt` speaker header case diverges from the oracle: app `[Speaker 1]`, oracle `[speaker 1]` (srt/vtt match) | LOW | `outputs/RenderTxt.kt:20-27` vs `docs/oracle/transcribe.py:419-426` | **Decision needed**: align app to oracle, or update oracle/docs |
| Diarization mis-separates speakers: sometimes distinct speakers come out under one id, sometimes one speaker is split across ids. Split has the merge remedy; unseparated has none (no split tool), and re-running reproduces the same clustering for the same input | MED | user report 2026-10-14; `diarization/Clustering.kt:8-11,30-36` (auto count = `SPEAKER_DISTANCE_THRESHOLD` 0.5448 from 18 LibriSpeech clips of 6 readers, else largest-gap cut in the last `maxSpeakers` gaps) | CAM++ embedding quality × hard-coded heuristics; levers: recalibrate the threshold on real recordings, per-task tuning, or the existing fixed-speaker-count mode — plan as a roadmap item if pursued |

## Frontend

| Issue | Sev | Evidence | Notes |
| ----- | --- | -------- | ----- |
| Queue pause/resume failure is completely silent: `queuePauseMachine` stores `error`, `QueueView` reads only `matches('sending')` — the toggle flips back with no message (the reorder error *is* rendered) | HIGH | `frontend/src/features/queue/QueueView.tsx:476-483` vs `:500-502` | Render `pause.context.error` |
| Settings load failure is unrecoverable: the machine's `failed` state declares `RETRY`, but nothing ever sends it — a failed `GET /api/settings` leaves a bare callout until remount | MED | `frontend/src/shared/state/settingsMachine.ts:55-58`; `features/settings/SettingsView.tsx:148` | Wire a retry action, or delete the dead event |
| Threads field: `Number.parseInt` of non-numeric input yields NaN → serialized as `null` → silently overwrites the stored value on save; Save is never disabled while the PUT is pending (double-submit) | MED | `features/settings/SettingsView.tsx:126`, `:119-138` | Validate or keep-as-string; in-flight guard |
| Settings state duplicated and drift-prone: each view instantiates its own `settingsMachine` (two unsynchronized GETs); `connectionMachine` accumulates `speakerChanges`/`settingsUpdate` unboundedly; `TaskTranscriptView` replays stale merge events into a redundant `/turns` refetch per visit | MED | `features/settings/SettingsView.tsx:46`; `features/queue/QueueView.tsx:235`; `shared/state/connectionMachine.ts:55,109-116`; `features/history/TaskTranscriptView.tsx:198-206` | Single shared settings actor; cap/prune the maps |
| MSW handlers drift from the backend: only `GET /api/settings` registered, backend also serves `PUT /api/settings` + `DELETE /api/settings/device-fallback`; per-test `server.use` papers over it, `pnpm dev:mock` errors on save/dismiss | MED | `frontend/src/shared/api/handlers.ts:165-167` vs `backend/.../http/SettingsRoutes.kt` | Add the two handlers from the docs/api contract |
| Dead exports: `listTasks`/`getTask` used only by tests, `taskDiarizing` nowhere; `tsconfig` leaves `noUnusedLocals`/`noUnusedParameters` off so `pnpm build` never flags them | LOW | `shared/api/client.ts:37,42`; `shared/api/fixtures.ts:10,33`; `tsconfig.json` | Remove or enable the tsc flags |
| Duplicated helpers: `errorMessage` re-implemented in SettingsView (shared `types.ts` has it), `clampCount` pasted in QueueView/SettingsView (one omits the radix), the actor `.stop()/.start()` restart idiom handwritten three times | LOW | `features/settings/SettingsView.tsx:231-233`; `features/queue/QueueView.tsx:153`; `TaskRow.tsx`/`HistoryRow.tsx`/`TaskTranscriptView.tsx` | Extract to `shared` |
| `uploadTask` assigns `xhr.upload.onprogress` unguarded — throws where XHR upload is unsupported | LOW | `shared/api/client.ts` (~`uploadTask`) | `xhr.upload?.onprogress = …` |
| Turn rows keyed by array index; a merge RELOAD replaces the whole array, weakening reconciliation | LOW | `features/history/TranscriptTurns.tsx` | Stable key (startMs + speakerId) |
| A long filename breaks a queue item line's layout: `RowName` contains it only with `overflow-wrap: anywhere` (no nowrap/ellipsis), so a long name wraps mid-word into a multi-line block and the row grows tall and ragged beside the status/controls — usable, but ugly (same helper renders upload rows) | LOW | user report 2026-10-14; `features/queue/rowParts.ts:20-23`; `TaskRow.tsx:192`; `QueueView.tsx:468` | Single-line truncation with `text-overflow: ellipsis` (`title` for the full name); check the History rows' name rendering for the same pattern |

## Tests

| Issue | Sev | Evidence | Notes |
| ----- | --- | -------- | ----- |
| Wall-clock/flaky patterns: 200 ms "nothing happens" negative windows, `withTimeoutOrNull(500 ms).shouldBe(null)` pause probes that pass vacuously when a slow machine hasn't reached the checkpoint, fixed `delay(2 s)` before `shouldBeEmpty`, 500 ms sleeps in process tests | MED | `engine/ProcessingEngineQueuePauseSpec.kt:35`; `engine/ProcessingEngineCancelSpec.kt:78,112`; `diarization/SpeakerIdsSpec.kt:224`; `diarization/EmbeddingsSpec.kt:97-98`; `http/EventsDisconnectSpec.kt:47`; `process/ProcessRunnerSpec.kt:376,559` | Replace negative real-time windows with signal-based determinism; item 47 is the same class of fix |
| Frontend `vitest` tests run on the default 5 s per-test timeout with no margin; under CPU contention with a concurrent `gradle` run 5 unrelated tests timed out (~8 s each) once — all pass when the suite runs alone | LOW | `frontend/vite.config.ts:40-44` (no `testTimeout`); `frontend/package.json:11` | Run the frontend suite alone (no concurrent gradle); raise `testTimeout` if it recurs |
| `EventBufferSpec` pins the literal `64` — asserts nothing behavioral | LOW | `engine/EventBufferSpec.kt:15-16` | **Decision needed**: delete |
| `queue/TestTransitions.kt` hand-transcribes `allowedTransitions` byte-identical to production `allowedTargets`; stays green if both change wrongly (the real anchor is the docs/api contract sweep) | LOW | `queue/TestTransitions.kt:19-31` vs `queue/TaskStatus.kt:33-41` | Drop the copy |
| `OnnxRuntimeSpec` loads the native ONNX Runtime and asserts CPU provider availability without the Integration tag the suite uses for environment-backed tests | LOW | `diarization/OnnxRuntimeSpec.kt:8-14` | **Decision needed**: tag Integration (borderline vs ADR 0021) |
| Two `internal fun segment(...)` test builders, same purpose, different packages, diverged signatures | LOW | `outputs/TestSegments.kt`; `diarization/TestSegments.kt` | One shared helper |
| Drop-oldest SSE overflow tested only on `sendEvents`, never through the real request path — the subscribed flow's buffering integration is untested | LOW | `http/EventsOverflowSpec.kt:9-14` | Route-level falling-behind-subscriber test |
| `15-screenshots` must take eight screenshots — with a viewport and theme switch — inside the ~77 s seed clip's live window, before it clicks Cancel on that row; a fast machine or a slow shot loop leaves no row to cancel | MED | `e2e/tests/15-screenshots.spec.ts:42-63` | Pause the queue once `Transcribing` is visible (the seeded row then waits instead of finishing), or gate the shots on the seed's phase |
| `06-playback`'s nothing-happens assertions are fixed sleeps (`waitForTimeout(500)`, `(1_500)`) and one waits for visible playback advance within 10 s: a regression landing after the window is a false pass, and a loaded machine can fail the advance | LOW | `e2e/tests/06-playback.spec.ts:74,80,91,113`; `:87` (`expect.poll(…, { timeout: 10_000 }).not.toBe(started)`) | Bind to signals (`audio.currentTime` deltas, pause/scroll events) instead of sleeps |
| `17-follow-mode`'s hidden-checks sleep 500 ms and its follow-scroll poll window is 5 s — fixed-time assumptions around an otherwise signal-based loop | LOW | `e2e/tests/17-follow-mode.spec.ts:80,143`; `e2e/tests/gifs-record.spec.ts:296` (same shape) | Replace the hidden-check sleeps with a scroll-settled poll |
| `gifs-record` (standalone, `pnpm record:gifs`; ignored by the journey — `e2e/playwright.config.ts:12`) sleeps 500/1000/4000 ms, has a negative window after one, and runs under a 900 s config timeout | LOW | `e2e/tests/gifs-record.spec.ts:187,242,293,298,327`; `e2e/playwright.gifs.config.ts:15` | Not CI-gating; align it with the journey's signal-based waits the next time it is touched. Item 47 fixed the same class in `03-upload-queue`, `17-audio-while-transcribing` and `40-atomic-transitions` |
| The atomic-transition race specs' writer-lock probe records a writer **before** its call joins the lock's queue, so "has called lock()" does not prove "is queued": if the OS preempts the first racer's thread between the record and `delegate.lock`, the second racer (launched once the record is seen) can queue first and invert the commit order. Very unlikely (the second racer does a DB read first) and present since item 40's call counter; found in item 55's review | LOW | `backend/src/test/kotlin/stt/engine/TestEngine.kt:306-309` (record, then `delegate.lock`); `:364-371` (`queueWrite` launches the next racer on the record) | kotlinx `Mutex` exposes no waiter queue; a fix needs a test-owned gate that hands the lock out in a fixed order instead of relying on the mutex's FIFO |

## Build, packaging, docs

| Issue | Sev | Evidence | Notes |
| ----- | --- | -------- | ----- |
| JDK 21 hardcoded four times in `backend/build.gradle.kts` (source/target compat, jvmTarget, `-Xjdk-release`, `--release`) while the authoritative `jdk` version lives in `versions.json`; `checkVersions` guards only gradle + nixpkgs rev, so a bump silently desyncs compile target from run JVM | MED | `backend/build.gradle.kts`; `versions.json`; root `build.gradle.kts` (`checkVersions`) | Backend reads the target from versions.json, or checkVersions covers jdk |
| Docker build cache busted by non-inputs: `COPY . .` keeps README/AGENTS/LICENSE/versions.json in the context though the flake's sources are `backend/`+`docs/api` / `frontend/`+`docs/api`; editing them re-runs the ~11-min nix build, contradicting the README's cache claim | MED | `Dockerfile`; `.dockerignore`; README § Docker | Exclude the non-input files |
| `e2e/lib/backend.mjs` comments still say `gradle --no-daemon run` and "gradle 8.14"; Gradle is 9.8 (ADR 0049) and the harness runs the installed distribution | LOW | `e2e/lib/backend.mjs:6,211-213,250-253` | Fix the comments |
| whisper/speaker model sha256s duplicated as "independent" expected hashes in `devenv.nix enterTest`; a model bump in `nix/models.nix` leaves `devenv test` silently stale or failing | LOW | `devenv.nix` (~:160-167); `nix/models.nix` | Derive from models.nix |
| Managed node/pnpm home path and the node/pnpm versions.json parse constructed independently in root and frontend `build.gradle.kts` — drift breaks PATH silently | LOW | root `build.gradle.kts` (`nodeInstallDir`); `frontend/build.gradle.kts` (`node.workDir`) | One shared source of truth |
| README § "The app as it ships, from source" runs `gradle run` without the three required env vars; works only inside the devenv shell, fails startup outside it (AGENTS.md states the requirement, the README does not) | LOW | README.md vs `config/Config.kt` | Show the full invocation or point at the shell |
| CI backend job can fail spuriously on upstream rate limiting: resolving the Kotlin plugin BOM on a cold Gradle home returned `429 Too Many Requests` from `repo.maven.apache.org`, failing the configuration phase before any task ran; the same commit passed on re-run | LOW | `.github/workflows/ci.yml:38`; run #1 attempt 1 job `116006313314` (`gradle check`, 0:30; `kotlin-gradle-plugins-bom:2.3.21.pom` → 429), attempt 2 green | **Decision needed**: accept-and-re-run, or add resolve-retry. Item 51's workflow sets `cache-read-only: false`, so PR runs restore and write the Gradle cache too; a 429 needs a cold resolution (a new gradle-home key, or a lost cache) |
| `docs/check-links.mjs` runs only on `README.md` and `docs/architecture.md` (AGENTS § Commands), so every other document's relative links go unchecked: `docs/known-issues.md`'s roadmap pointer list carried four links missing their `../` and resolving to `docs/roadmap/*` until 2026-10-15 | LOW | `docs/check-links.mjs`; AGENTS.md § Commands; `docs/known-issues.md:18-23` (fixed) | Run the checker over every `docs/**/*.md` and `roadmap/*.md` in the docs command and the CI job |

---

*Initial filing: 2026-10-13 from the six-pass read-only review of `main` (backend core, backend
support/migrations, backend tests, frontend app, frontend shared/config, build/Nix/Docker/e2e/docs);
top findings re-verified against the source before filing. Add: when a review, failure, or report
finds a real problem. Remove: when a roadmap item is created that handles the issue.*