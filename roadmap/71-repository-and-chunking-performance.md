# 71 — Repository and chunking performance: indexed queue queries, one list query, bounded chunking memory

Status: draft
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

## Open questions

- The exact index set (single-column vs composite; whether `status` alone suffices for every plan) —
  decided by `EXPLAIN QUERY PLAN` on the real queries.
- The peak-heap bound the long-clip test asserts, and which fixture it uses (a generated 20-minute
  clip or an existing long fixture).
- Whether `listDetails`'s two per-task values can move into a materialized column instead of a
  grouped query (the simpler option wins by measurement).

## Test list (proposed; to be approved at the consultation)

- [ ] The queue and list queries use an index: their query plans show a search, not a full table
      scan, and stay the same shape as the data grows
- [ ] One list/snapshot request issues a constant number of SQL statements, not two per task
- [ ] A long clip's chunking peak heap stays under the agreed bound, and the stored chunk rows are
      byte-identical to before
- [ ] (must not break) The backend unit and Integration suites are green; queue ordering and
      history output are unchanged

## Acceptance criteria

- Indexes exist for the hot queue queries; `listDetails` is one query; chunking memory is bounded and
  measured; the database and API behavior are unchanged.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (indexes, `listDetails`, CHUNKING
  memory); not yet discussed.
