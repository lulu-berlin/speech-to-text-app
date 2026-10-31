# 85 — CI wall time: a code pull request's checks finish sooner without proving less

Status: spec-approved
Depends on: [84](84-e2e-reliability.md) (it returns `Windows (e2e)` to the aggregate, which makes
that job the run's critical path, and it is still rewriting the journey's waits and counting its
five green Windows runs on the current job layout)
Blocks: — (after 1.0, decided 2026-10-31)

## Goal

`CI (all checks)` on a code pull request turns green sooner, measured from the run's start, while
every suite still proves what it proves today: no spec dropped, no platform dropped, no wait
weakened.

## Evidence

- **Where a run's time goes** (CI run 41112050517, #421, a whole-run dispatch on item 84's branch,
  2026-10-30; minutes from the run's start, read from the public jobs API):

  | Job | Starts | Takes | Ends |
  |---|---|---|---|
  | Linux / macOS / Windows (gradle check) | 0:12 | 2:22 / 2:35 / 3:09 | 2:34 / 2:47 / 3:21 |
  | Linux / macOS / Windows (pnpm test, pnpm build) | 0:12 | 1:16 / 1:40 / 2:01 | 1:28 / 1:54 / 2:13 |
  | Linux (e2e) | 2:37 | 15:09 | 17:46 |
  | macOS (e2e) | 2:50 | 11:46 | 14:36 |
  | Windows (e2e) | 3:23 | 17:40 | 21:03 |
  | Linux (bundle e2e), the longest other job | 2:37 | 10:42 | 13:19 |
  | CI (all checks) | 17:49 | 0:03 | 17:52 |

  The aggregate waited for Linux (e2e); `Windows (e2e)` is reporting-only since #80 and finished
  three minutes after it. Once item 84 returns it to the aggregate, a green run takes about 21 min.
  Run #422 (41159132098) has the same check durations (2:22–3:11).
- **Every heavy job waits for its platform's check jobs**: the journeys, the integration tests, the
  packaged-app e2e jobs and the Nix jobs all carry `needs: [changes, <platform>-backend-check,
  <platform>-frontend-check]` (`.github/workflows/ci.yml:147,166,236,250,280,317,615,643,1044,1153,
  1191,1227,1269,1281`), so none of them starts before 2:37 (Linux), 2:50 (macOS) or 3:23
  (Windows).
- **The journey is one worker**: `e2e/playwright.config.ts:13` (`workers: 1`), because every spec
  shares one backend and its queue. Its report took 763 s on Linux and 922 s on Windows in #421.
- **Six specs take most of the journey** (item 84's measurement, runs #413, #415, #421):
  `17-follow-mode` 175–211 s, about 25–30% of every journey, because it plays real audio at normal
  speed; with the transcription-wait specs `81-held-pause` (31–109 s), `10-cancel-retry` (24–166),
  `06-playback` (25–70), `40-pause-queued` (18–54) and `59-status-delete` (27–59) they are 62–64%
  of every journey, and each varies 2–4.6x between runs of the same tree.
- **Setup is a minute or two per journey job**: inside the Playwright report setup is at most 10 s;
  the step around it (frontend build, `installDist`, fixtures) adds 55–99 s; the job around the
  step (checkout, toolchains, caches) added 1:33 on Linux and 0:40 on Windows in #421.
- **Item 84 set sharding aside for reliability, not for speed**: its measurement found the
  Windows journey's variance, not its mean, breaking the 20 min bound, so it fixed waits instead
  of adding jobs (`roadmap/84-e2e-reliability.md`, Approval log, 2026-10-30, finding 5).
- **Parallel jobs are capped**: GitHub-hosted standard runners allow 20 concurrent jobs and 5
  concurrent macOS jobs on the Free plan, 40 and 5 on Pro
  ([Actions limits](https://docs.github.com/en/actions/reference/limits#job-concurrency-limits-for-github-hosted-runners)).
  #421 ran 23 jobs; after the checks, 14 heavy jobs ran at once, 4 of them on macOS. Starting the
  heavy jobs with the checks would put 6 macOS jobs and about 22 jobs at once against those caps,
  so part of the gain would turn into queueing; which plan the repository runs on is not recorded
  here.

## Levers (from item 84's workspace analysis, 2026-10-31)

| Lever | Evidence | Expected effect | Cost / risk |
|---|---|---|---|
| Start the heavy jobs without waiting for the check jobs | Windows (e2e) started 3:23 into #421, after `windows-backend-check` and `windows-frontend-check` | About 3 min off every run | Runner minutes spent on runs whose checks fail; the macOS cap of 5 concurrent jobs |
| Shard the journey across 2 jobs (Playwright `--shard`) | `workers: 1`; the Windows journey report is 922 s | Close to half of the ~15 min journey per job | Twice the jobs; each shard needs its own backend and setup (~1–1.5 min); order-dependent specs (`13-restart` and others) stay in one shard |
| Shorten `17-follow-mode` | 189–211 s per run, about 25–30% of the journey; real audio at normal speed | Up to 2–3 min off every journey | A shorter clip or faster playback weakens what it tests: following the playhead in real time, hand scrolls, resuming |
| Shorter fixtures for the transcription-wait specs (`81-held-pause`, `10-cancel-retry`, `06-playback`, `40-pause-queued`, `59-status-delete`) | With `17-follow-mode` 62–64% of each journey; each varies 2–4.6x | Some minutes, depending on load | Each clip must stay long enough for the state its spec holds (pausing mid-transcription, canceling, …) |

Item 84's workspace recommended starting with the first row (it changes only `needs` in `ci.yml`),
then sharding, and leaving `17-follow-mode` alone unless its coverage is renegotiated.

## Decided (consultation, 2026-10-31)

- **No fixed target** (the user): the agreed levers are applied and each one's effect is measured
  and recorded; there is no wall-time number to reach.
- **Five runs per measurement** (the user): the baseline and every lever are measured over 5
  consecutive green whole-run dispatches, the bar item 84 set for Windows.
- **Levers in scope** (the user): start the journeys without the check jobs; shard the journey;
  shorter fixtures for the transcription-wait specs; cut per-job setup with caches.
  **Out of scope**: shortening `17-follow-mode` — its real-time coverage stays as it is.
- **Only the three journeys stop waiting for the checks** (the user): `e2e`, `macos-e2e` and
  `windows-e2e` need only `changes`; every other heavy job keeps failing fast behind its platform's
  checks. On the Free plan's caps that starts 3 macOS jobs at once (two checks and the journey) and
  4 after the checks, within the limit of 5.
- **The account is on GitHub Free** (the user): 20 concurrent jobs, 5 concurrent macOS jobs.
- **Sharding is decided after measuring the no-wait lever** (the user): which journeys, how many
  shards, or none, chosen with the user on that lever's numbers.
- **After 1.0** (the user): not a gate of item 78.
- **Exempt from ADR 0046's per-feature e2e test** (the user): the item changes CI and the e2e
  harness, not an app feature; the existing journeys are its proof.

## Proposal

1. **Measure the baseline**: the run's wall time to `CI (all checks)` and each job's start and
   duration, over 5 green runs the user dispatches once item 84 has merged, with `Windows (e2e)` in
   the aggregate.
2. **Apply the levers one at a time**, each measured over 5 runs before the next: the journeys
   start without the check jobs; then the sharding decision and, if chosen, the shards; shorter
   fixtures for the transcription-wait specs; caches for the journeys' setup.
3. **Keep what CI proves**: the same specs on the same platforms, the docs-only skip (item 80) and
   the reporting/gating split of `docs/check-ci-gating.test.ts` unchanged except where this item
   says otherwise; `docs/specs/ci-verification.md` amended for every structural change.

## Open questions

None. (All settled 2026-10-31, see "Decided (consultation, 2026-10-31)".)

## Test list (approved 2026-10-31; behaviors only)

- [ ] (measure) The baseline is recorded: wall time to `CI (all checks)` and each job's start and
      duration over 5 consecutive green whole-run dispatches with `Windows (e2e)` gating
- [ ] (no-wait) The three journeys start as soon as the change classification has run, while
      every other heavy job still waits for its platform's check jobs; the gating check holds that
      split, and a failing check job still fails `CI (all checks)`; the effect over 5 runs is
      recorded
- [ ] (shard decision) The sharding decision (which journeys, how many shards, or none) is recorded
      with the user, on the no-wait lever's numbers
- [ ] (shard, if chosen) A sharded journey runs every spec exactly once across its shards, each
      shard against its own backend, and the specs that depend on earlier specs' state run in one
      shard in their order; the effect over 5 runs is recorded
- [ ] (fixtures) Each of `81-held-pause`, `10-cancel-retry`, `06-playback`, `40-pause-queued` and
      `59-status-delete` runs on a clip shortened to what its held state needs and still reaches
      and asserts that state on all three journeys; the effect over 5 runs is recorded
- [ ] (setup) The journey jobs restore their dependencies, browsers and built UI from caches, a
      changed input (a lockfile, a frontend build input) misses the cache instead of serving stale
      content, and a cold run still passes; the effect over 5 runs is recorded
- [ ] (must not break) Each journey runs the same specs as before (55, `18-macos-metal` skipped off
      macOS, counted across shards), a docs-only pull request still skips every heavy job, and the
      packaged desktop suites are unchanged

## e2e stance

Exempt from ADR 0046's per-feature e2e test (decided 2026-10-31): the item changes CI and the e2e
harness, not an app feature, and the existing journeys running unchanged are its proof.

## Acceptance criteria

- The baseline and every applied lever's effect are recorded in this item's log, each over 5
  consecutive green runs with `Windows (e2e)` gating.
- Every journey runs the same specs on the same platforms as before; the docs-only skip and the
  gating check still hold.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-31: filed as a draft by the root agent at the user's request, from the lever analysis
  item 84's workspace gave the user (2026-10-31, answering "Are there any other optimizations planned
  to reduce wall time?") and the job timings of CI runs #421 and #422.
- 2026-10-31: consultation with the user: no fixed target, each lever measured over 5 consecutive
  green runs; levers in scope: the journeys start without the check jobs (only those three),
  sharding decided on that lever's numbers, shorter transcription-wait fixtures, setup caches;
  `17-follow-mode` stays as it is; the account is on GitHub Free; after 1.0; exempt from ADR 0046.
  Test list proposed.
- 2026-10-31: the user approved the test list as written; status set to spec-approved.
