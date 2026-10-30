# 71 — Repository and chunking performance: indexed queue queries, one list query, bounded chunking memory

Status: done (roadmap item 71, approved 2026-10-30)
Depends on: —
Blocks: —

## Goal

Queue and history reads stay bounded as the database grows, and CHUNKING no longer holds a long clip
in heap two to three times over: the list/snapshot and queue queries use indexes, `listDetails` runs
one grouped query instead of two per task, and chunking streams the canonical clip instead of
reading it whole.

## Evidence

- **No index exists in any migration**: `CREATE INDEX` appears nowhere under
  `backend/src/main/resources/db/migration/`; `TasksTable` has only its primary key, while
  `nextQueued`, `pendingOrderedIds`, `startedPaused`, `nextQueuePosition` and `retryIfPossible`
  filter/sort on `status` and `queue_position` (`db/TaskRepository.kt:102-151`).
- **`listDetails` is O(N) queries**: it runs an `audio_header exists` and a `segments` max-end query
  per task inside the read transaction (`db/TaskRepository.kt:428-447`), so every list and every SSE
  snapshot pays two extra queries per task.
- **Chunking copies the clip repeatedly**: the encoded FLAC is read whole
  (`audio/AudioChunker.kt:72`, `Files.readAllBytes`), the splitter copies the header and every frame
  out of it (`audio/FlacSplitter.kt:99,107`), and `toCanonicalRows` joins each second's frames into
  another array (`audio/FlacRows.kt:36` via `engine/ProcessingEngine.kt:537`). Measured 2026-10-16
  (item 43's long-clip run, heap after GC): a 20-minute 16 kHz mono clip reaches ~100 MB right after
  upload; a 90-minute clip is several hundred MB transient, while serving and diarization stream one
  row at a time.
- All three were filed from the 2026-10-13 six-pass review; CHUNKING's numbers were measured
  2026-10-16 in item 43's long-clip run. None was in its item's scope (item 44's review left the
  chunking copy in place).

## Proposal

1. A new migration adds the indexes the queries need (`tasks(status)`, `tasks(queue_position)`, or a
   composite where the plan prefers it), with the query plans checked before/after.
2. `listDetails` becomes one grouped query (a join/left join on `audio_header` and a grouped
   `MAX(endMs)` per task) so a list/snapshot issues a constant number of queries.
3. CHUNKING streams: split from a file channel (or memory-map the FLAC), build the one-second rows
   during the existing frame walk without the extra join array, and keep the stored bytes identical.
4. Record the index set, the grouped query's shape and the peak-heap measurement in the item's log.

## Decided (consultation, 2026-10-28)

- **The index set is chosen by `EXPLAIN QUERY PLAN`**, not by preference: `tasks(status)`,
  `tasks(queue_position)` or a composite only where a single-column index still leaves a scan. The
  chosen set and both plans (before/after) go into the migration's comment and this item's log.
- **`listDetails` is one grouped query**, not a materialized column: one left join on `audio_header`
  plus a grouped `MAX(endMs)`; a materialized column is reconsidered only if the grouped query
  measures slower on a filled database. The test pins the statement count, which is the behavior.
- **The memory proof is a forked-JVM Integration test**: a generated 20-minute, pseudo-random
  (incompressible) 16 kHz mono clip — no committed fixture — chunked in a child JVM whose max heap is
  the bound recorded in this item's log (set after the first measurement, so today's whole-file read
  exceeds it), with the stored rows compared byte-for-byte. It runs no whisper pass: the shipped
  audio-only ffmpeg gave a 20-minute incompressible clip a 0.43 s canonical encode and a 0.27 s
  `silencedetect` pass (measured 2026-10-28), so the test is seconds. If a cycle measures it above
  60 s, use a 10-minute clip with a proportionally smaller cap.

## Test list (approved; behaviors only)

- [x] The queue and list queries use an index: their query plans show a search, not a full table
      scan, and stay the same shape as the data grows
- [x] One list/snapshot request issues a constant number of SQL statements, not two per task
- [x] Chunking a generated 20-minute clip in a child JVM under the recorded heap bound completes,
      and the stored chunk rows are byte-identical to before
- [x] (must not break) The backend unit and Integration suites are green; queue ordering and
      history output are unchanged

## e2e stance

The item changes only how the backend reads and stores data it already reads and stores: new
indexes, one grouped list query, and a streaming CHUNKING path whose stored rows stay byte-identical.
No app feature changes and no API response changes, so it adds no feature e2e. ADR 0046's
build/packaging/refactor-only exemption does not literally cover performance items, so the reason is
stated here, as in items 65 and 79; the existing e2e journey keeps covering the list, queue order and
playback these changes touch.

## Acceptance criteria

- Indexes exist for the hot queue queries; `listDetails` is one query; chunking memory is bounded and
  measured; the database and API behavior are unchanged.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (indexes, `listDetails`, CHUNKING
  memory); not yet discussed.
- 2026-10-28: consultation with the user: the memory proof is the forked-JVM test over a generated
  20-minute clip under the bound recorded in the log (measured ffmpeg cost makes it seconds); the
  index set and `listDetails`'s shape stay measurement-decided. Test list approved as written;
  status set to spec-approved.
- 2026-10-29: e2e stance approved with the user: exempt from ADR 0046's e2e requirement, since no
  app feature or API response changes (§ e2e stance).

## Log

- 2026-10-29, `listDetails` (test-list item 2): one grouped statement, shared with `details(id)` —
  `tasks` inner-joined with `audio`, left-joined with `audio_header` (its `task_id` only, never the
  blob) and with `segments`, `GROUP BY tasks.id`, `MAX(segments.end_ms)`. A list of three tasks went
  from 7 statements (1 + 2 per task) to 1.
- 2026-10-29, index set (test-list item 1), from `EXPLAIN QUERY PLAN` on SQLite 3.50.3
  (`TaskQueryPlanSpec`, 6 tasks and again at 6 000): `tasks_status` on `tasks (status)` and
  `tasks_queue_position` on `tasks (queue_position)`, migration V14. No composite: with the two
  single-column indexes no queue plan scans `tasks`; the `TEMP B-TREE` left in `nextQueued`,
  `pendingOrderedIds` and `startedPaused` sorts only the rows the status lookup found. The list's
  one pass over `tasks` is by design (it returns every task); its joins were already lookups. The
  app never runs `ANALYZE`, so the plans do not depend on the row count, which the 6 → 6 000 check
  confirms.

  | Query | Before | After |
  | --- | --- | --- |
  | `nextQueuePosition` | `SCAN tasks`, temp B-tree for ORDER BY | `SEARCH tasks USING INDEX tasks_queue_position (queue_position>?)` |
  | `startedPaused` | `SCAN tasks`, temp B-tree for ORDER BY | `SEARCH tasks USING INDEX tasks_status (status=?)`, temp B-tree |
  | `pendingOrderedIds` | `SCAN tasks`, temp B-tree for ORDER BY | `SEARCH tasks USING INDEX tasks_status (status=?)`, temp B-tree |
  | `nextQueued` | `SCAN tasks`, temp B-tree for ORDER BY | `SEARCH tasks USING INDEX tasks_status (status=?)`, temp B-tree |
  | `retryIfPossible` | `SCAN tasks`, temp B-tree; update by rowid | `SEARCH tasks USING INDEX tasks_queue_position (queue_position>?)`; update by rowid |
  | `recoverRunning` | `SCAN tasks` | `SEARCH tasks USING INDEX tasks_status (status=?)` |
  | `listDetails` | `SCAN tasks`; `audio`, `audio_header` by rowid; `segments` by `sqlite_autoindex_segments_1 (task_id=?)` | unchanged |
- 2026-10-30, CHUNKING memory (test-list item 3), `ChunkingHeapBoundIntegrationSpec`: a 20-minute
  16 kHz mono white-noise clip (seeded `java.util.Random`, 38.4 MB of PCM, ~38.5 MB canonical FLAC)
  chunked and stored in a child JVM (`-Xmx`, `-XX:+ExitOnOutOfMemoryError`). Before: the run needs
  84 MiB of heap (out of memory at 80 MiB; ~7 s at 84–256 MiB). After: it runs in 12 MiB (out of
  memory at 8 MiB, the JVM's baseline with Exposed, Flyway and SQLite loaded). **Recorded bound:
  48 MiB**; the red run at 48 MiB ended in `OutOfMemoryError` (exit 3), the green one completes in
  8.7 s, so the 20-minute clip stays (no 10-minute fallback). Shape: the splitter walks the encoded
  file through a 64 KiB-block `FileChannel` reader (`FileFlacBytes`), keeping only the header and
  the frame end offsets (`FlacLayout`); `ChunkRepository.storeChunked(taskId, chunked)` — the call
  the engine makes — reads each one-second row from the file while inserting it. No memory map: a
  mapped file cannot be deleted on Windows until the mapping is collected, and the engine removes
  the chunker's scratch right after storing. The stored rows are checked against an independent
  encode: header + rows equal ffmpeg's canonical FLAC of the same source byte for byte, 1 200 rows
  of four frames, row k starting with frame 4k.
- 2026-10-30, must-not-break (test-list item 4), on the branch rebased onto `main` `a5d1ecdf`:
  `gradle check` exit 0 (386 unit tests, 1 skipped, the platform skip that predates the item);
  `gradle integrationTest` exit 0 (85 Integration tests ran, among them
  `ChunkingHeapBoundIntegrationSpec`, `AudioChunkerSpec`, `FlacSplitterSpec`, `AudioMigrationSpec`
  (V10's backfill), `StreamedDiarizationIntegrationSpec` and `ProcessingEngineCanonicalStorageSpec`;
  `E2eTasksManagedNodeIntegrationSpec` skipped in the sandbox, whose managed Node cannot execute).
  Queue order and history output stay pinned by the existing `TaskListSpec` (pending in queue order,
  then finished newest `finishedAt` first), `TaskRoutesSpec` (`hasAudio`, `transcribedUntilMs`,
  the task JSON) and `QueueReorderSpec`; all green without change. Docs package steps green (each
  run directly with `node`, the sandbox lacking `/bin/sh` for the `.bin` shims): `tsc`, prettier,
  the five checks, 30/30 `node --test`. One earlier full `gradle check` on a loaded host failed
  `EventsHeartbeatSpec`'s wall-clock window (filed in `docs/known-issues.md`); it passed alone and
  in the three full runs since.
