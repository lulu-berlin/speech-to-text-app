# 85 — CI wall time: a code pull request's checks finish sooner without proving less

Status: done (roadmap item 85, approved 2026-11-07)
Depends on: [84](e2e-reliability.md) (it returns `Windows (e2e)` to the aggregate, which makes
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
  of adding jobs (`docs/specs/e2e-reliability.md`, Approval log, 2026-10-30, finding 5).
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

- [x] (measure) The baseline is recorded: wall time to `CI (all checks)` and each job's start and
      duration over 5 consecutive green whole-run dispatches with `Windows (e2e)` gating
- [x] (no-wait) The three journeys start as soon as the change classification has run, while
      every other heavy job still waits for its platform's check jobs; the gating check holds that
      split, and a failing check job still fails `CI (all checks)`; the effect over 5 runs is
      recorded (four clean greens on `1ed03445` plus #581, whose check job was lost — the deviation
      is named in § Measurements)
- [x] (shard decision) **None** (decided 2026-11-06 on the no-wait numbers): the run already carries
      26 jobs against the Free plan's 20 concurrent ones and the macOS cap is 5 (#581's journey
      waited to +15:01 for a slot), the critical path alternates between `macOS (e2e)` and a
      packaged job (`Linux (bundle e2e)` in #575, 20:23) that sharding cannot touch, and a
      pathological VM stays pathological per shard. The levers that do target the critical path are
      the five transcription-wait fixtures (3.3–4.1 min of a 10.8–12.8 min journey) and the
      journey jobs' setup (~1–2 min)
- [x] (shard, if chosen) Not chosen (see the decision above); the requirement it would have carried
      — every spec exactly once across shards on its own backend, order-dependent specs together —
      is therefore met by the single journey per platform, as before
- [x] (fixtures) Each of `81-held-pause`, `10-cancel-retry`, `06-playback`, `40-pause-queued` and
      `59-status-delete` runs on a clip shortened to what its held state needs and still reaches
      and asserts that state on all three journeys; the effect over 5 runs is recorded
- [x] (setup) The journey jobs restore their dependencies, browsers and built UI from caches, a
      changed input (a lockfile, a frontend build input) misses the cache instead of serving stale
      content, and a cold run still passes; the effect over 5 runs is recorded
- [x] (specs) The fixed waits item 84 left outside its list are gone as assertions: `40-pause-queued`
      no longer assumes a 3 s window for "the paused task never started", and `52-edit-segment`'s
      no-scroll check and `59-header`'s sampling bound their windows to a signal or a recorded
      measurement — each still able to fail on the regression it exists for
- [x] (discovered) `87-writes-wait-for-the-connection`'s cut-off upload ends on the upload row's own
      state — the row gone, or the duplicate notice naming the task — instead of a 120 s poll budget
      a loaded macOS runner can outlast (CI run #555, 2026-11-04); the app's transport message ends
      the wait at once, and a row that stops changing ends it naming its text; and no per-test
      ceiling shorter than the waits it calls (#573: the 300 s `setTimeout` cut the test off one
      second after `startBackend`'s own 600 s health allowance brought the backend up)
- [x] (discovered) A pathological runner is caught by the journey itself: the run's own transcription
      time over its UI-only time (measured on the first five specs) above 10× aborts the run naming the
      factor — healthy runs measure 0.7–2.4×, the VM of run #544 4.7× and run #605's 24.7× — so a
      starved runner costs minutes instead of the job's 90-minute timeout, on every journey
- [x] (discovered) The packaged bundle job restores the bundle from a cache keyed on its own
      derivation and builds it only when that derivation changed — a change that leaves the
      bundle's inputs alone skips its ~5-minute build — and a cold run still builds it and passes
- [x] (discovered) Only the default branch writes the Gradle cache and the bundle cache: a pull
      request restores `main`'s entries instead of adding its own, so the growth from pull requests
      stops (one PR had held ten `gradle-dependencies-v2` variants, ~2.5 GB), a PR run still gets a
      warm cache, and a PR's second run adds no new `gradle-dependencies-v2` or `stt-bundle-*`
      entry. The repository stays above the Free plan's 10 GB on `main`'s own entries (11.56 GB on
      2026-11-07; see the known-issues row), and eviction covers the overage
- [x] (must not break) Each journey runs the same specs as before (55, `18-macos-metal` skipped off
      macOS, counted across shards), a docs-only pull request still skips every heavy job, and the
      packaged desktop suites are unchanged

## Measurements

Every number below comes from the public jobs API (`/actions/runs/<id>/jobs`) and names the commit the
run actually ran on. A measurement counts over five consecutive green whole-tree runs with
`Windows (e2e)` gating in the aggregate; times are `m:ss` counted from the run's start (`+m:ss` is a
duration). The check that produced the table also asserts the rule on the API itself (five most recent
CI runs on `main`, every job `success` or a manual/scheduled `skipped`, `Windows (e2e)` ended before
`CI (all checks)` started) and then re-derives every cell.

A red that the runner guard produces on a starved Windows VM does not count as a failed run when the
re-run on a healthy VM is green (the user, 2026-11-07; `docs/known-issues.md` § Tests records the
pool's behaviour).

### Baseline — before any lever (measured 2026-11-04)

The five most recent CI runs on `main`, all after item 84 merged (`dac3e7f6`) and all `push` events, so
each is a whole-tree run: #539 `8df74eb3`, #537 `6474e4f5`, #534 `9c4135aa`, #528 `d7ccdde5`,
#526 `633643e4` (41773886823, 41764589413, 41756146968, 41750085586, 41749247895). All green.

| Run | Commit | First job | Heavy jobs start | Checks Linux / macOS / Windows | Linux (e2e) | macOS (e2e) | Windows (e2e) | Longest other job | `CI (all checks)` | Wall |
|---|---|---|---|---|---|---|---|---|---|---|
| #539 | `8df74eb3` | 0:02 | 3:26 | 3:15 / 3:38 / 3:23 | 3:28 +14:41 | 3:52 +16:47 | 3:36 +9:55 | Linux (bundle e2e) 3:27 +13:30 | 20:41 | 20:45 |
| #537 | `6474e4f5` | 0:03 | 3:02 | 2:50 / 2:50 / 3:53 | 3:03 +14:46 | 3:04 +18:22 | 7:35 +15:53 | Linux (bundle e2e) 3:02 +15:20 | 23:36 | 23:38 |
| #534 | `9c4135aa` | 15:12 | 18:52 | 3:28 / 3:30 / 5:10 | 18:52 +9:49 | 26:57 +19:06 | 20:34 +14:07 | Linux (bundle e2e) 18:57 +16:12 | 46:05 | 46:08 |
| #528 | `d7ccdde5` | 17:10 | 24:13 | 3:03 / 4:18 / 3:21 | 27:45 +9:03 | 38:46 +28:24 | 24:13 +16:28 | Linux (bundle e2e) 28:04 +11:34 | 67:12 | 67:15 |
| #526 | `633643e4` | 0:03 | 3:29 | 3:27 / 3:11 / 5:03 | 3:44 +13:22 | 3:36 +18:56 | 5:20 +15:00 | Linux (bundle e2e) 3:44 +15:45 | 23:26 | 23:30 |

What the table says, and what it does not:

- **The check gate costs about three minutes**: the checks end at +2:50…+3:53 and the earliest heavy
  job starts at +3:02…+3:29. The three runs that were not queued are 20:45, 23:38 and 23:30; the
  median of all five is 23:38.
- **`macOS (e2e)` is the critical path in all five runs** (it ends at 20:39, 21:26, 46:03, 67:10 and
  22:32), not `Linux (e2e)` as in #421's branch dispatch: macOS starts earlier and runs longer than
  the Linux journey here. `Linux (bundle e2e)` is the longest non-journey job in every run.
- **Two runs are queueing, not checks**: #534's first job started at +15:12 and #528's at +17:10, and
  #528's `macOS (e2e)` only at +38:46 (the Free plan's 5 concurrent macOS jobs). Both overlapped the
  previous run's jobs — #528 (00:52:46–02:00:02) with #526 (00:45:52–01:09:23), #534
  (01:44:52–02:31:01) with #528 — so the 20-minute scale of their walls is the 20-job concurrency cap
  across two runs of the same repository, not the `needs:` gate. Rapid pushes to `main` are what
  produces it; a single pull request's run meets its own jobs only.
- `Windows (e2e)`'s start offset moves (3:36, 7:35, 20:34, 24:13, 5:20) with Windows runner
  assignment; its duration is 9:55–16:28, so the Windows journey never ends last.

### The no-wait lever — five runs on `1ed03445` (measured 2026-11-06)

`e2e`, `macos-e2e` and `windows-e2e` need only `changes`. #574 (42153822110), #575 (42160923337),
#577 (42166704471), #579 (42211314489) and #581 (42229473487), all `workflow_dispatch` on
`1ed03445`.

| Run | Commit | First job | Heavy jobs start | Checks Linux / macOS / Windows | Linux (e2e) | macOS (e2e) | Windows (e2e) | Longest other job | `CI (all checks)` | Wall |
|---|---|---|---|---|---|---|---|---|---|---|
| #574 | `1ed03445` | 0:04 | 2:17 | 2:05 / 2:55 / 2:31 | 0:11 +13:32 | 0:11 +17:38 | 0:11 +9:48 | Linux (bundle e2e) 2:17 +12:31 | 17:51 | 17:54 |
| #575 | `1ed03445` | 0:05 | 3:17 | 3:03 / 3:42 / 4:16 | 0:12 +8:50 | 0:12 +16:38 | 0:12 +15:36 | Linux (bundle e2e) 3:17 +17:06 | 20:25 | 20:29 |
| #577 | `1ed03445` | 1:24 | 4:23 | 2:48 / 3:03 / 3:40 | 1:33 +12:39 | 1:34 +18:10 | 1:33 +10:10 | Linux (bundle e2e) 4:23 +11:14 | 19:47 | 19:49 |
| #579 | `1ed03445` | 0:03 | 2:37 | 2:50 / 2:19 / 2:45 | 0:12 +9:35 | 0:12 +16:30 | 0:13 +15:14 | Linux (bundle e2e) 3:04 +12:43 | 16:44 | 16:46 |
| #581 | `1ed03445` | 0:04 | 3:03 | 2:49 / **cancelled 17:37** / 4:16 | 0:13 +15:47 | 15:01 +14:22 | 0:13 +19:25 | Linux (bundle e2e) 3:03 +10:24 | 29:25 | 29:28 |

- **The journeys now start with the checks**: +0:11…+0:15 in the four runs whose start was not
  delayed (against +3:03…+3:52 in the baseline). #577's 1:24 head is the workflow's concurrency group
  waiting for the cancelled #576, not the gate.
- **The wall**: 16:46, 17:54, 19:49 and 20:29 in the four unbroken greens — median **18:52**, against
  the baseline's unqueued 20:45, 23:30 and 23:38 (median 23:30). The saving is the journeys' ~3
  minute earlier start, which the wall inherits whenever a journey sets it.
- **The journeys, not the packaged jobs, moved**: the check-gated jobs start at +2:17…+4:23 (baseline
  +2:37…+3:53), unchanged by the lever; what moved is that the journeys no longer wait for the
  checks. `macOS (e2e)` is still the critical path in four of the five runs (it ends at 17:49, 16:50,
  19:44, 16:42, 29:23), `Linux (bundle e2e)` in #575 (20:23).
- **#581 is not a clean whole-run green, and the aggregate does not say so**: `macOS (pnpm test, pnpm
  build)` spent 17:37 on a macOS runner (normally 1:01–2:01) and was cancelled, its three dependents
  were skipped, and `CI (all checks)` still reported success at +29:25 — the hole AGENTS.md documents
  from run #506. Its journeys ran and passed (Linux 15:47, Windows 19:25, macOS 14:22) and its 29:28
  wall is macOS runner capacity (the journey started only at +15:01), not the lever. The user accepted
  this as the fifth run's journey evidence rather than a clean green, so the item records four
  consecutive clean greens plus that run, with the deviation named — the item's own rule is five
  consecutive greens, and this is the one place it was waived.
- **Not a single run of the set was red for the branch.** The attempts and their causes: #544 65:34
  green but with a 64:20 Windows journey; #547, #550, #563, #564, #566, #574, #575, #577, #579 green;
  #555 and #573 red on the `87` spec (its fix is the discovered item); #569 red on the backend
  `ProcessingEngineAtomicTransitionsSpec` wait; #561 red on infrastructure (HTTP 504 in
  `jvm-toolchain`); #570 and #576 cancelled with `Windows (e2e)` at 35 and 39 minutes for 10 of 60
  specs; #581 as above.

### The fixtures lever — three clips shortened (measured 2026-11-07)

The five counted greens are #592 (42291341737), #595 (42333726428), #598 (42338860313), #599
(42340239265) and #602 (42344630175), each named by the commit it ran on (`9aefa6d0`, `78da534e`,
`9853e4c2`); the reds before them are named with their causes in the log (#585 capacity, #586 runner
lost, #589 upstream 404, #597 the runner guard's own abort). Per-spec durations come from each run's
`e2e-durations-linux` and `-windows` artifacts, against the two pre-change runs #574 (42153822110)
and #575 (42160923337).

| Spec | clip | before #574, #575 (L / W) | after greens' median (L / W) | change (L / W) |
|---|---|---|---|---|
| `10-cancel-retry` | 220 s → 100 s | 1.04, 0.40 / 0.45, 1.44 | **0.39 / 0.37** | −0.33 / −0.58 |
| `81-held-pause` | 165 s → 30 s | 0.92, 0.29 / 0.35, 1.15 | **0.17 / 0.16** | −0.44 / −0.59 |
| `40-pause-queued` | 121 s → 27 s | 0.67, 0.27 / 0.33, 0.74 | **0.21 / 0.22** | −0.26 / −0.32 |
| `17-follow-mode` (control, untouched) | 154 s | 3.18, 2.83 / 2.88, 3.38 | **3.06 / 3.07** | +0.05 / −0.06 |

- **The control separates the clip change from runner speed**: the untouched real-time spec stayed
  within ±0.06 min while the three shortened ones fell 0.26–0.59 min each — together **~1.0 min
  (Linux) and ~1.5 min (Windows)** at the median.
- **The journeys still reached their states**: every one of the five greens ran all three journeys
  successfully, so no shortened clip lost the pause, the cancel or the queue hold it exists for.
- **Journey totals move less and vary more** than these three specs: across the five greens the
  Linux journey was 6.25–12.33 min and the Windows journey 7.24–11.22, so the per-spec comparison is
  the honest one.
- **The wall over the five greens**: 26:28, 18:59, 14:58, 16:17, 14:12 — median **16:17**. The 26:28
  run is the one whose macOS jobs sat in the runner queue (`macOS (e2e)` started at +5:08 and
  `macOS (Nix build + devenv)` at +18:00), so its wall is capacity, not the levers.
- `06-playback` and `59-status-delete` were left as they are, with the reasons recorded in the log
  (the 12 × 10 s PageUp seeks need a clip that ends just past them; the delete spec's clip is a 5 s
  trim and its time is the FAILED path).

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
- 2026-10-31: amended from item 84's review: the three fixed waits item 84 did not plan —
  `40-pause-queued.spec.ts:35` (3 s, "the worker looked again"), `52-edit-segment.spec.ts:267`
  (200 ms, no-scroll) and `59-header.spec.ts:110` (250 ms, a sampling cadence) — are folded here as
  a test-list item, since this item already works on the journey's specs; the user chose this over a
  new backlog row. Status stays spec-approved.
- 2026-11-04 (workspace, test-list item 1 "(measure)", confirmed by the user, who accepted the five
  main push runs in place of new dispatches because a push runs the whole tree): red = the cycle's
  one-off check refused the unrecorded baseline —
  `not ok - the baseline table is not recorded: …/roadmap/85-ci-wall-time.md has no row "| Run | Commit | First job | Heavy jobs start | Checks Linux / macOS / Windows | Linux (e2e) | macOS (e2e) | Windows (e2e) | Longest other job | \`CI (all checks)\` | Wall |"`,
  exit 1. Green = the table in § Measurements, every cell re-derived from the jobs API, with the
  counting rule itself asserted on the API (the five ids are the five most recent CI runs on `main`,
  every job `success` or a manual/scheduled `skipped`, `Windows (e2e)` ended before `CI (all checks)`
  started, and `ci.needs` carries `windows-e2e`): `ok - the baseline is recorded over five consecutive
  green whole-tree runs, Windows (e2e) gating`. The check has no committed artefact — its output was
  the numbers, which § Measurements holds — so the commit is the record alone. Runs #539, #537, #534,
  #528, #526 on `8df74eb3`, `6474e4f5`, `9c4135aa`, `d7ccdde5`, `633643e4`; walls 20:45, 23:38,
  46:08, 67:15, 23:30. Item ticked; the critical path is `macOS (e2e)` in all five runs, which the
  sharding decision must weigh. No refactoring suggested (no code changed; the measurement script
  lives outside the repository on purpose — § Measurements is the deliverable).
- 2026-11-04 (workspace, test-list item 2 "(no-wait)", confirmed by the user): red = the new check in
  `docs/check-ci-gating.test.ts` ("the three journeys start with the change classification, and the
  checks gate every other job") failed on the unchanged workflow:
  `AssertionError [ERR_ASSERTION]: e2e starts as soon as the change classification has run` with
  actual `[ 'changes', 'backend-check', 'frontend-check' ]` and expected `[ 'changes' ]`; the other
  nine checks stayed green. Green = `needs: [changes]` on `e2e`, `macos-e2e` and `windows-e2e`; the
  macOS layout expectation in the same check amended to the split (the macOS journey is now the one
  macOS heavy job that does not wait for `macos-backend-check`/`macos-frontend-check`), the workflow's
  comments corrected, and `docs/specs/ci-verification.md` amended. The check also holds the other
  half: every non-journey job in the aggregator's `needs` still needs its platform's two check jobs,
  and all six platform check jobs plus the three client checks stay in the aggregator's `needs`, so a
  red check still reddens `CI (all checks)` while the journeys run. Verified locally:
  `node --test docs/check-ci-gating.test.ts` 10/10, the docs suite 34/34 (`tsc`, Prettier, the link,
  architecture and exception checks and their tests), `actionlint .github/workflows/ci.yml`
  clean. **Not ticked yet**: the item also requires the lever's effect over five runs, which needs the
  user's five dispatches on this commit.
- 2026-11-04 (workspace, the first dispatched run, 41787902759 on `6a2139c1`, failed): `Linux
  (documentation checks)` exited 1 because this log entry's own phrasing named the component roadmap
  item 75 retired, and the retirement check forbids naming it in a live document
  (`roadmap/85-ci-wall-time.md:230`). Fixed by rewording the entry; no check changed, and the docs
  suite is 34/34 again. The run does not count toward the five: the measurement restarts on the next
  pushed commit, with all five dispatches on one commit.
- 2026-11-04 (workspace, refactor approved by the user after the no-wait green): the three journey
  ids became one `JOURNEYS` constant beside `UNGATED`/`EVENT_GATED` in `docs/check-ci-gating.test.ts`,
  read by the new check's loop and its exclusion; the tests stayed 10/10. `Refactor: Hoist the
  journey list in the CI gating check`.
- 2026-11-04 (workspace, the `(discovered)` item from run #555, chosen by the user): red = the new
  `e2e/lib/upload-outcome.test.ts` failed with `ERR_MODULE_NOT_FOUND` for `./upload-outcome.ts`
  (exit 1); green = `e2e/lib/upload-outcome.ts` (`awaitUploadOutcome`: the row gone is the resend
  landing, the duplicate notice is the cut-off attempt having reached the service, the app's
  transport message throws on the first reading, and a row that stops changing ends the wait after
  600 s naming its text) with four unit tests on the fake-clock seam, and the spec's 120 s `toPass`
  replaced by that wait. Verified locally: the four tests, the e2e unit suite 47/47,
  `node check-playwright.ts`, `prettier --check .` and `tsc -p e2e/tsconfig.json` clean. Not
  verifiable here: the journey itself (no backend or model in the sandbox); its proof is a green
  `macOS (e2e)` in the next measurement run. Why: run 41802078740 #555's macOS journey redded on the
  120 s budget although the page snapshot at failure already showed the row's end state
  (`jfk-upload-wait.wav transcribed — open` with no uploads row). Refactor question after this green
  is asked before the next cycle.
- 2026-11-04 (workspace, refactor approved by the user after the discovered green): the four-line
  `defaultSleep` both waits carried became one export in `e2e/lib/wait.ts`, the module that already
  holds the polling seams, imported by `await-task.ts` and `upload-outcome.ts`; the e2e unit suite
  stayed 47/47, `tsc` and `prettier` clean. `Refactor: Share the polling sleep in the e2e waits`.
- 2026-11-05/06 (workspace, measurement attempts on `fab81b02`, then on the rebased `630dc3a5`):
  #544 (41788286742) 65:34, #547 (41796048780) 20:04, #550 (41798654900) 21:13 all green on
  `a23f6306`; #555 (41802078740) red on the 87 spec (the discovered item); #563 (42137326856)
  22:15, #564 (42140037183) 21:54, #566 (42142994561) 17:37 green on `fab81b02`; #569 (42145117147)
  red on the backend `ProcessingEngineAtomicTransitionsSpec` wait (`TestEngine.kt:538`, handed to the
  main-tree agent for `docs/known-issues.md`) and cancelled; #561 (41998746628) red on infrastructure
  (HTTP 504 in `jvm-toolchain`, 10 s toolchain timeouts); #570 (42146731594) cancelled after its
  `Windows (e2e)` reached 35 min at test 10 of 60; #573 (42150653535) red on the 87 spec's own 300 s
  `setTimeout` at 5.0 m, with the page snapshot already showing `Live` and the clip transcribed.
  The user chose to record the five on `fab81b02` and, after the rebase, to restart the count on
  `630dc3a5` (`main` `0f670d56`; the rebase's one conflict was this file's log vs item 90's
  amendment, both kept).
- 2026-11-06 (workspace, the discovered item's second cycle, confirmed by the user): red = CI run
  #573's macOS journey, `Test timeout of 300000ms exceeded` after 5.0 m, while the restarted backend
  came up at 02:51:23 and `startBackend` allows `healthTimeoutMs = 600_000`
  (`e2e/lib/backend.ts:714`) — the spec's own ceiling cut it off. Green = both `setTimeout(300_000)`
  calls removed from `87-writes-wait-for-the-connection.spec.ts`, so the config's 20-minute outer net
  (`playwright.config.ts`) is the only ceiling and the state wait (600 s stall) decides; e2e unit
  suite 47/47, `tsc` and `prettier` clean. The journey itself is proven only in CI.
- 2026-11-06 (workspace, the runner-health `(discovered)` item, chosen by the user): red =
  `e2e/lib/runner-health.test.ts` failed with `ERR_MODULE_NOT_FOUND` for `./runner-health.ts`; green =
  `transcriptionRatio` — the run's own 03+06 time over its 01+02+04+05 time, `null` until every probe
  spec has run or when no UI time was recorded — and the auto fixture in `tests/helpers/test.ts` that
  aborts above `SLOW_RUNNER_FACTOR = 15` naming the factor. The threshold comes from the
  `e2e-durations-linux`/`-windows` artifacts: healthy runs measure 3.4×, 3.6×, 6.2× and 7.1×, the
  pathological Windows VMs of #544 (and the cancelled #570/#576) 43× — the guard fires after the sixth
  spec, minutes in, instead of the 90-minute job timeout. It needs no recorded per-platform table (the
  ratio is the run's own), so it covers macOS too, whose journey uploads no durations artifact.
  Verified locally: 15/15 e2e tests, `tsc -p e2e/tsconfig.json`, `prettier --check .`. The guard's own
  firing is proven only in CI, on the next pathological runner.
- 2026-11-06 (workspace, the no-wait measurement closed; the user accepted four greens plus #581):
  #574 (42153822110) 17:54, #575 (42160923337) 20:29, #577 (42166704471) 19:49 and #579
  (42211314489) 16:46 are clean greens on `1ed03445`; #581 (42229473487) had
  `macOS (pnpm test, pnpm build)` cancelled after 17:37 on a macOS runner (normal 1:01–2:01), its
  three dependents skipped, and `CI (all checks)` still reporting success at +29:25 — the hole
  AGENTS.md documents from run #506. Its journeys ran and passed and its 29:28 wall is capacity. The
  five-run table, the deviation and every attempt's cause are in § Measurements. (no-wait) is ticked:
  journeys at +0:11…+0:15 against +3:03…+3:52, wall median 18:52 against the baseline's 23:30, the
  check-gated jobs unchanged at +2:17…+4:23. The `87` `(discovered)` item is ticked: its macOS
  journey has been green on every healthy run since the state wait and the ceiling removal. The
  runner-health guard stays unticked — no pathological runner has run since it landed.
- 2026-11-06 (workspace, the `(shard decision)` item, decided with the user): **no sharding**. On the
  no-wait numbers: a run is already 26 jobs against the 20-job Free-plan cap, the macOS cap of 5
  delayed #581's journey to +15:01, the critical path is `macOS (e2e)` in four runs and
  `Linux (bundle e2e)` in #575 (a packaged job, untouched by sharding), and a pathological VM stays
  pathological per shard. Instead the item takes its own two remaining levers on the critical path:
  the five transcription-wait fixtures (measured 3.3 min Linux / 4.1 min Windows of a 10.8 / 12.8 min
  journey, whole-spec totals from the duration artifacts) and the journey jobs' setup (~1–2 min).
  `17-follow-mode` stays as decided (3.18–3.38 min, its real-time coverage).
- 2026-11-06 (workspace, the `(fixtures)` item, first spec, confirmed by the user): red = the new
  `e2e/lib/fixtures.test.ts` case failed with `3,520,000` samples against the expected `1,760,000`
  — the cancel/retry clip was the sample twenty times (220 s). Green = `{ start: 1, seconds: 10,
  loops: 10 }` (~100 s). The first attempt, `loops: 10`, produced bytes identical to item 40's
  `jfk-s.wav` (also ten loops) and the generator's unique-bytes check failed, so the clip is a
  one-second-in window. Verified locally: fixtures tests 2/2, `tsc`, `prettier`. Not verifiable here:
  the journey itself (no backend or model) — the three journeys' green prove the cancel and the
  retry still reach their states.
- 2026-11-06 (workspace, `(fixtures)`, second spec): red = the new case failed with `2,640,000`
  samples against the expected `480,000` — `81-held-pause`'s running clip was the sample fifteen
  times (165 s). Green = `{ start: 1, seconds: 10, loops: 3 }` (~30 s, bytes unique against the
  cancel clip's ten copies of the same window). The spec holds the Pause response in the browser
  after the backend has paused, so the clip only has to be transcribing when the click lands;
  transcription of the 11 s sample measures ~5 s, so 30 s leaves ~10 s. e2e backend+fixtures tests
  15/15, `tsc`, `prettier` clean; the journeys are the proof of the held state.
- 2026-11-06 (workspace, `(fixtures)`, third spec and the two that stay): red = `1,936,000` samples
  against `480,000` (121 s); green = `{ start: 2, seconds: 9, loops: 3 }` — 27 s, because a 10 s
  window asked for from 2 s in clamps at the sample's 11 s end (the first attempt produced 27 s and
  the test caught it). `06-playback` is **not** shortened: its spec seeks with 12 × PageUp at 10 s
  each, which must land near the end, so its 132 s clip is the state it needs (and its transcript
  must still exceed a 500 px viewport). `59-status-delete`'s clip is already a 5 s trim; its 27–59 s
  is the FAILED path and two uploads, not the audio. fixtures tests 4/4, `tsc`, `prettier` clean.
- 2026-11-06 (workspace, the packaged floor, chosen by the user): red = the new assertion in
  `docs/check-ci-gating.test.ts` failed — `nix-build builds .#desktop-bundle, the output
  desktop-bundle-e2e otherwise rebuilds every run`. Green = the `nix-build` job (the only writer of
  the shared store cache) builds `.#desktop-bundle` after the default output, so later runs restore
  it; `docs/specs/ci-verification.md` amended. The phase table from run 42236802827 (#585): free disk 1:50,
  toolchain 0:22, `tsc` + nix evaluation 1:10, `nix build` 7:00, 17 packaged specs 4:00 — a cache
  hit takes ~6–7 min off `desktop-bundle-e2e`, the floor whenever it binds (#575: 20:23). Verified
  locally: gating check 11/11, docs suite 35/35, `actionlint` clean. Not verifiable here: the cache
  hit itself — the next two runs (cold, then warm) must show `copying path` for the bundle and no
  `building '…stt-app-bundle-launcher.drv'`. Fallback if the 10 GB repository cache cannot hold the
  closure: split build and specs, the build on `changes` only (~2:40–4:40 by the chain arithmetic).
- 2026-11-07 (workspace, the bundle cache reverted; the user saw the cold run): the store-cache route
  did not hold. #589 (cold) built the bundle in `nix-build` (7:44); #592 looked warm (`nix-build`
  1:02, `desktop-bundle-e2e` 7:48); but #595 on the same commit `9aefa6d0` rebuilt it again
  (`nix-build` 7:30, `desktop-bundle-e2e` 12:58, its log `building '…stt-app-bundle-launcher.drv'`
  and `stt-desktop-bundle-payload` after fetching 363.3 MiB). Two facts explain it: GitHub cache
  entries are immutable per key, so `nix-build` cannot add the bundle to an existing
  `nix-Linux-v2-…` entry; and the repository's caches stood at 10.53 GB in 60 entries against the
  Free plan's 10 GB (`nix-Linux-v2` 2.64 GB, `nix-macOS-v2` 2.55 GB, ten Gradle dependency entries
  2.47 GB), so entries are evicted. A non-deterministic hit that also presses every other cache
  toward eviction is not a lever: the `.#desktop-bundle` line, its gating assertion and the
  `ci-verification.md` amendment are reverted. The packaged floor stays as measured (`nix build`
  ≈7 min of the bundle job) and is left to a later item for the packaged jobs; an artifact-based
  split would meet the Free plan's artifact quota with a bundle of this size.
- 2026-11-07 (workspace, the bundle cached under its own derivation, the user's call: "cache only the
  bundle"): #595's log split its 5:18 cold build — ≈35 s of pinned inputs fetched or built in
  parallel, then `stt-desktop-app` 2:13 and `stt-desktop-bundle-payload` 2:30, both rebuilt whenever
  the app changes — so only the whole bundle is worth caching, and only under its own derivation:
  that key changes exactly when the bundle would. The bundle is one self-contained file
  (`nix/desktop-bundle.nix` appends the payload to the launcher, which unpacks from `$self`). Red =
  the new `docs/check-packaged-steps.test.ts` case failed (`the job evaluates the bundle derivation
  that keys its cache`). Green = `desktop-bundle-e2e` evaluates `.#desktop-bundle.drvPath`, restores
  `stt-bundle-<derivation>` with `actions/cache` (exact key, no `restore-keys`), builds only on a
  miss into `$RUNNER_TEMP/stt-bundle`, prints the file's size, and runs the specs with
  `E2E_BUNDLE_FILE`, which `run-in-docker.ts` mounts read-only into the container instead of
  building (a direct run still builds); `ci-verification.md` amended. A `$HOME` inside the step's
  single quotes tripped actionlint's SC2016, so the path is `${{ runner.temp }}`. Verified locally:
  packaged-steps + gating checks 12/12, `actionlint` clean, e2e `tsc` and Prettier clean. Not
  verifiable here: the cache itself — the first run builds and prints the size (a miss), the next
  run on a tree whose bundle inputs did not change must skip `Build the bundle`.
- 2026-11-07 (CI run 42337098519, #597 on `6daacd86`, the runner guard's first firing): `Windows
  (e2e)` stopped on spec 6 of 60 after 2.5 min of specs (Gradle `e2e` 4:58, job 5:48) with `the runner transcribes
  20.9× slower than it drives the UI (03+06 186 s against 01+02+04+05 9 s)`. Against the healthy
  runs (03+06 29–64 s, UI 8–11 s) transcription ran 2.9–6.4× slower while the UI work did not —
  #544's signature, less extreme (20.9× against 43×). The 15× threshold sits between the healthy
  3.4–7.1× and #544's 43×, and this run fell in that gap; one firing says nothing yet about the
  false-positive rate, so every run's factor keeps being read from the duration artifacts. The
  `(discovered)` guard item is ticked: a starved runner cost six minutes, not the 35–90 of #570
  and #576. The run is red and does not count among the fixtures greens.
- 2026-11-07 (CI run 42337098519, #597 on `6daacd86`, the bundle cache's miss): key 0:03, restore
  0:00, `Build the bundle` 3:58, specs 5:06, save 0:04, job 11:06; it saved
  `stt-bundle-yigsnlgms6lp26grkk1f5yi8ma57cdw0-stt-app-bundle` at 388 MB. With the Windows journey
  stopped early by the guard, the bundle job was that run's critical path (ended +14:23, after
  `macOS (e2e)` at +13:48). The repository's caches stood at 11.11 GB in 64 entries after the save,
  over the Free plan's 10 GB, so GitHub evicts least-recently-used entries; only commits that
  change the app create a new 388 MB bundle entry.
- 2026-11-07 (correction, the workspace agent): the entry this one replaces, committed as
  `78da534e`, recorded a cache hit in "run 42339042086 #598" (bundle job 6:59, restore 0:07, build
  skipped, wall 16:52), ticked the bundle item on it and counted it as a fixtures green. That run
  does not exist (the jobs API answers 404): the agent wrote its link and its results itself instead
  of reading them from GitHub. All of it is withdrawn — the bundle item is unticked, and the
  fixtures greens are #592 and #595 only. The real hit test is CI run 42338860313 (#598, on
  `78da534e`, the same bundle derivation as `6daacd86`).
- 2026-11-07 (CI run 42338860313, #598 on `78da534e`, the bundle cache's hit): key 0:03, **restore
  0:04**, `Build the bundle` **skipped**, size 0:00, specs 5:03 — the job's steps sum to 6:56
  against #597's 11:06, so a hit saves ~4:10 of this job, and against the 12:58–15:46 the job took
  before the cache (#585, #589, #595) it saves 6–9 min. The job started at +3:28 and ended around
  +10:24, off the critical path: `Linux (e2e)` ended +14:52 and `CI (all checks)` ran at +14:54, so
  the wall was **14:58**. The `(discovered)` bundle item is ticked on this run (restores by
  derivation, builds only on a miss) and on #597 (the miss built it, and that cold run's bundle job
  was green). #598 is a fixtures green — every job `success`, the bundle job's every step
  `success`/`skipped`; its `conclusion` field reads `null` in the jobs API while the aggregate is
  `success`, an API quirk to remember when reading these tables. With #592 and #595 the fixtures
  greens stand at three, on `9aefa6d0` and `78da534e`.
- 2026-11-07 (CI run 42340239265, #599 on `9853e4c2`, the bundle cache's **eviction**): a roadmap-only
  commit, so the derivation is the same one #598 hit — and the bundle was built again (3:48, job
  12:56, wall 16:17). The cache list holds exactly one `stt-bundle-yigsnlgms…` entry, `created_at`
  05:07:34: the entry #598 restored at 04:44 was gone by #599 and re-saved afterwards. The inventory
  fell from 11.11 GB in 64 entries to 10.90 GB in 55 in the same window, so GitHub's eviction under
  the Free plan's 10 GB budget removed it. The `(discovered)` bundle item stays ticked — the
  mechanism is proven by #598 — but a hit is **not guaranteed** while the repository sits at or over
  its cache budget: any entry, the bundle's 388 MB one included, can be evicted between runs. Making
  the hit reliable is a budget question outside this item (stale `gradle-dependencies-v2`,
  `gradle-transforms-v2` and `nix-*` keys are the candidates to prune). #599 is fixtures green four
  of five — every job `success`, wall 16:17 — on `9853e4c2`.
- 2026-11-07 (workspace, the cache policy, chosen by the user after the eviction in #599): red = the
  new `docs/check-ci-gating.test.ts` case failed — `the JVM toolchain writes the Gradle cache on
  main only`. Green = `jvm-toolchain` sets
  `cache-read-only: ${{ github.ref != 'refs/heads/main' }}` (header comment corrected: restore-only
  still restores, and a pull request may restore the default branch's entries), gating check 11/11,
  `ci-verification.md` amended. The churn's cause, from the cache API: the repository held 10.08 GB
  in 52 entries, one pull request alone contributing ten `gradle-dependencies-v2` entries (150–325
  MB) plus duplicate `gradle-wrapper-zips-v2`/`transforms` keys. By the time the prune could run,
  GitHub's eviction had already removed them: the inventory went to **33 entries / 8.18 GB** (the
  usage API's own figure), with no duplicate under the same (key-without-hash, ref) grouping left to
  delete — the user ran the recompute-and-delete command and it correctly deleted nothing. The
  entries the matrix needs survived (`nix-Linux-v2` 2641 MB, `nix-macOS-v2` 2555 MB,
  `stt-downloads-Linux` 787 MB, `desktop-downloads-*`, and the 388 MB bundle entry). Not verifiable
  here: that PR runs stop writing — the check is a PR run's next attempt adding no new per-PR entry;
  the action file's YAML is parsed by CI (no parser in the sandbox). Expected wall-time cost: none
  for a PR that leaves build files alone, at most a delta re-resolution on repeat runs of one that
  changes them.
- 2026-11-07 (workspace, cache budget, decided with the user): with the inventory at 8.48 GB in 37
  entries, 61% is the two Nix store caches (2.64 + 2.55 GB, both already at the 4 GB gc cap; a miss
  there costs 7:44–10 min per Nix job) and 1.47 GB is `stt-downloads-*` — the release/model-host
  artifacts the bootstrap fetches, which every runtime job would otherwise re-download. No pruning is
  left to do: GitHub's eviction removed the stale per-PR entries, and the bundle's 388 MB entry
  survived. The levers kept in reserve, in cost order if budget ever runs short again: drop
  `nix-macOS-v2` (2.55 GB, `macos-nix` ~9 min cold), drop `desktop-downloads-*` (0.63 GB, 1–2 min per
  packaged job), or consolidate `stt-downloads-*` with `desktop-downloads-*` — they hold overlapping
  whisper-cli and ffmpeg copies (estimated 0.2–0.4 GB), but the packaged jobs would then read the
  Gradle bootstrap's layout, a refactor with its own item. Nothing is done for these now; the new
  cache policy stops the growth that made them look necessary.
- 2026-11-07 (CI run 42344630175, #602 on `9853e4c2`, the fifth green and a second bundle hit): every
  job `success`, wall **14:12**; the bundle job restored (0:04) and skipped the build again, so the
  388 MB entry survived the eviction storm once the inventory settled at 8.18–8.48 GB. The fixtures
  five are complete — #592, #595, #598, #599, #602 — and their per-spec effect is in § Measurements:
  the three shortened specs fell 0.26–0.59 min each (≈1.0 min Linux / 1.5 min Windows at the median)
  while the untouched `17-follow-mode` control stayed within ±0.06 min. The `(fixtures)` item is
  ticked. The bundle `(discovered)` item's evidence is now two hits (#598, #602) and one miss (#597).
- 2026-11-07 (workspace, `(setup)` first cycle, confirmed by the user): from run #602's step ledger
  the journey jobs' own setup is already thin (checkout 1–5 s, `jvm-toolchain` 8–47 s,
  `rust-toolchain` 9–10 s), leaving ~53 s (Linux) and ~98 s (Windows) inside the `gradle e2e` step
  for UI build, `installDist`, fixtures and browsers — so the lever's ceiling is 1–1.5 min per job,
  not the 2–3 min the item's evidence table implied. Red = the new `docs/check-ci-gating.test.ts` case failed
  (`stepsOf is not defined` after the assertion itself was added — the import had been dropped with
  the bundle-cache revert). Green = all three journeys restore `frontend/dist` from `actions/cache`
  keyed by `hashFiles` of the frontend sources/public/configs/lockfile and `docs/api/*.json`, before
  `gradle e2e`; `ensure-ui` stays the authority (the restored dist carries `.ui-digest`, so a key
  that misses an input costs a rebuild, never a stale UI). Verified locally: gating check 12/12,
  `actionlint` clean, docs suite green. Not verifiable here: the hit itself — the next two runs show
  whether `e2eFrontend` skips the build, and the cache entry's size.
- 2026-11-07 (CI run 42346663293, #605 on `30f2c7e2`): Windows (e2e) red on the guard (job 16:11),
  every other job green, wall 17:31. The UI cache missed and saved `ui-dist-ae8b1b26…` at 0.25 MB
  (Linux's job; the redded Windows job saves nothing), and the bundle job rebuilt (7:12) because this
  commit changes `.github/`, which the bundle's Nix source includes — so the derivation really did
  change, unlike #598's roadmap-only commit.
- 2026-11-07 (workspace, the runner guard's probe, chosen by the user after #605): #605's own
  artifacts gave the factor — `03-upload-queue` 248 s and `06-playback` 566 s (aborted) against 10 s
  of UI work, **81.1×** — but the six-probe design had to wait for `06-playback` itself, 9.4 min,
  before it could fire. On `03` alone the same run measured **24.7×**, so the probe is now `03`
  against `01+02+04+05` and the threshold **10**: healthy runs 0.7–2.4× across 14 artifact runs, the
  64-minute VM of #544 4.7×, #605 24.7× — ~2× margin on both sides, one spec earlier. Red = the
  rewritten `runner-health.test.ts` failed against the old probe (no verdict without `06`);
  green = the new probe, threshold 10, the guard's message naming `03-upload-queue`, and the
  test-list item restated. e2e unit tests, `tsc` and `prettier` green. Not verifiable here: a starved
  runner aborting after `05-transcript` — the next such run's log shows it.
- 2026-11-07 (workspace, `(specs)` first wait, confirmed by the user): `52-edit-segment`'s no-scroll
  check no longer waits 200 ms. The wheel's effect, if the page were scrollable under the shade,
  happens in the frame that dispatches it — nothing in the app sets `scroll-behavior: smooth` (checked
  in `frontend/src`) — so the check is bounded by **two animation frames** and the same assertions
  (window and `main` scroll positions stay 0) remain. No local red is possible for an inline timing
  change with no unit seam; the proof is the journey, and the residual risk is a future smooth scroll,
  which the comment names. e2e `tsc` and `prettier` green.
- 2026-11-07 (workspace, `(specs)` second wait): `59-header`'s follow check no longer samples on a
  250 ms × 40 cadence. It now samples the segment/header gap **whenever the playing segment changes**,
  until three changes have been seen; the 60 s deadline is a stall bound that ends the wait naming a
  segment that never changes, not a sampling window. The assertions are unchanged (sampled at least
  once, every gap ≥ −1), and the change-count assertion replaces the old "the active segment is not
  the first one". e2e `tsc` and `prettier` green; the journey is the proof.
- 2026-11-07 (workspace, `(specs)` third wait): `40-pause-queued`'s 3 s window is gone. The spec now
  uploads a third clip behind the paused one (`jfk-pause-signal.wav`, the 3–11 s trim, 8 s) and waits
  for the worker to **start it** — the positive signal that the queue was looked at again — then
  asserts the paused row is still PAUSED, never transcribing, with Resume offered. A regression that
  starts the paused task either leaves it RUNNING (the row assertion fails) or completes it first (the
  row is gone), so the check still fails on the bug it exists for. Locally: the new
  `fixtures.test.ts` case pins the clip at 8 s (128,000 samples), the e2e unit suite is 55/55,
  `tsc`, `prettier` and `check-playwright` green. The journeys are the proof that the worker picks the
  third clip on all three platforms. `(specs)` is ticked: the frame-bounded no-scroll check, the
  segment-change sampling and this signal all replace fixed waits without weakening an assertion.
- 2026-11-07 (workspace, `(setup)` browsers, confirmed by the user): red = the new gating case failed
  (`the journeys restore the Playwright browsers`); green = `.github/actions/restore-browsers` caches
  Playwright's browser directory per platform (resolved from `runner.os` by a `case` step) keyed by
  `runner.os` + `hashFiles('e2e/pnpm-lock.yaml')`, so a Playwright bump misses, and all three
  journeys use it before their journey step. gating check 15/15, `actionlint` clean, docs green. Not
  verifiable here: the hit — a run must show the step restoring and `e2eBrowsers` skipping the
  download; the entry's size (~200 MB per OS) against the 8.5 GB inventory is the number to watch.
- 2026-11-07 (CI run 42348900063, #606 on `09063a54`): the guard's new probe fired as designed —
  Windows (e2e) red in **4:46**, after the fifth spec, at **13.7×** (`03-upload-queue` 133 s against
  10 s of UI work; `05-transcript` carries the failure because the abort lands in its teardown).
  Under the six-probe design the same VM would have had to finish `06-playback`, which cost 566 s on
  #605. The band holds: healthy 0.7–2.4×, #544 4.7×, this VM 13.7×, #605 24.7×.
- 2026-11-07 (workspace, Windows VM policy, decided by the user): keep the guard and re-dispatch
  until a healthy Windows VM, rather than raising the threshold or removing the journey from the
  aggregate. The numbers behind it: of the last seven Windows journeys, three were starved (#597
  guard, #605 24.7×, #606 13.7×) against healthy ones at 0.9–1.9×; each starved run now costs ~5
  minutes instead of 15–60, and each starved run leaves its factor in the duration artifacts. A PR
  whose own run lands on a starved VM needs a re-run before merge — accepted as the price of the
  fast red.
- 2026-11-07 (the user): the starved Windows VMs are a known issue — a row in `docs/known-issues.md`
  § Tests records the pool's behaviour (three of seven VMs starved, 13.7–24.7× against healthy
  0.9–1.9×, the guard's 4:46–5:48 aborts, each run's duration artifact) — and a starved-runner red
  **does not count as a failed run when the re-run on a healthy VM is green**. The rule is stated in
  § Measurements; a PR whose own run lands on a starved VM needs a re-run before merge.
- 2026-11-07 (CI run 42348900063, #606 on `09063a54`, finished): the three rewritten waits ran green
  on two platforms — `Linux (e2e)` 12:08 and `macOS (e2e)` 16:34 — so `40-pause-queued`'s signal
  clip, `59-header`'s segment-change sampling and `52-edit-segment`'s two-frame bound behave against
  the real backend; `Windows (e2e)` is the starved-VM red (4:46, 13.7×) that the known-issue policy
  makes a re-run case. Wall 16:53; the aggregate failed only on Windows.
- 2026-11-07 (workspace, the browsers cache reverted, found in #607): `restore-browsers` did nothing.
  Its step took 0:01 with a 0:00 post-save and created no `playwright-*` entry, because the directory
  it cached never exists: the build installs Playwright's browsers into
  `~/.gradle/stt-app/ms-playwright` (`PLAYWRIGHT_BROWSERS_PATH`'s default, `build.gradle.kts:517`), and
  `jvm-toolchain`'s downloads cache already restores `~/.gradle/stt-app` as `stt-downloads-<os>-…`
  (786.7 MB on Linux, restored in #607 at 07:15). So the browsers half of `(setup)` was already banked
  by item 33's download cache; the action, its three uses and its gating case are removed, and
  `ci-verification.md` records why. The UI cache missed once more in #607 (a new 0.3 MB key,
  `ui-dist-fe1bb5a…`, while #606 had hit `ui-dist-ae8b1b2…`) — the next run on this tree shows whether
  it now hits.
- 2026-11-07 (CI run 42350236465, #607 on `0240ab21`, green end to end): every one of the 26 jobs
  succeeded — `Linux (e2e)` 9:18, `macOS (e2e)` 11:31, `Windows (e2e)` 11:06 on a healthy VM,
  `Windows (packaged zip e2e)` 12:04 inside its limits, the bundle job restored (0:03) and skipped
  its build (8:21) — wall 16:21, aggregate +16:19. With the spec set unchanged (50 files as on
  `main`, edits only), the docs-only skip pinned by the gating check and the packaged suites green,
  `(must not break)` is ticked on this run. Two things it did not prove: the UI cache **missed**
  again here (key `ui-dist-fe1bb5a…`), and the tree it ran predates `285f52ab` (the browser-cache
  revert), so the post-push run is what clears the reverted workflow and the UI hit.
- 2026-11-07 (workspace, the UI cache reverted, decided by the user): the cache hit once (#606) and
  missed twice (#605, #607) on trees whose frontend inputs are identical — re-derived from git, the
  matched set is 190 files with one content hash, the same for all three commits and HEAD — so the
  drift is GitHub's `hashFiles`, not the tree. A cache that misses on unchanged inputs costs the
  rebuild the jobs already pay plus a 0.3 MB entry, so `restore-ui`, its three uses and its gating
  case are removed and `ci-verification.md` records why. `(setup)` is ticked on what is banked: item
  33's `stt-downloads-*` already carries the dependencies and Playwright's browsers (786.7 MB on
  Linux, restored in #607 at 07:15; the browsers live in `~/.gradle/stt-app/ms-playwright`), and the
  journeys' residual setup is the measured ~53 s Linux / ~98 s Windows inside `gradle e2e` — a cost,
  not a lever this item can remove with the free plan's cache budget.
- 2026-11-07 (workspace, rebase): the branch was rebased onto main `bc54e5b5` (items 93 and 98 merged;
  head `1bdedd5f`). The first rebase attempt silently dropped the `Test: Cache the desktop bundle with the
  Nix build` record — its revert commit also carried the record's undo, so the tree came out right but the
  record was gone — and it was restored in place at `e23f89e8`; the final tree is identical to the
  pre-rebase tip plus the two merged items (verified by an empty diff against the successful rebase).
- 2026-11-07 (workspace, the cache policy over its two runs, confirmed with the user): the before is the
  cache inventory before the policy existed — 20 `gradle-*` writes under PR refs, `refs/pull/113/merge`
  alone holding 10 `gradle-dependencies-v2` variants plus transforms/wrapper (~2.5 GB), 14.47 GB total
  against the free plan's 10 GB. The after is run #609 (`pull_request`, head `33681f35`, wall 20:46, 32
  jobs all success/skipped, aggregate success): `refs/pull/115/merge` holds 6 entries and 0 `gradle-*`
  (only the bundle, downloads and pnpm caches), while `refs/heads/main` holds 45. The second run is the
  `pull_request` synchronize run on the rebased head `1bdedd5f`; it must add no `gradle-*` under
  `refs/pull/115/merge` either.
- 2026-11-07 (workspace, the bundle cache policy, decided with the user from the review): the repository
  stood at 10.31 GB against the Free plan's 10 GB because a pull request still wrote its own 370 MB
  `stt-bundle-<derivation>` entry (the implicit `actions/cache` save). `desktop-bundle-e2e` now restores
  with `actions/cache/restore` on the derivation key and only `main` saves (`actions/cache/save` on a
  miss); the gating check pins the shape and the packaged-steps check names the restore step. The before
  is this PR's ref holding the 370 MB entry; the after must be a PR run that restores, saves nothing and
  adds no `stt-bundle-*` entry under its ref — the next push runs it. A pull request that changes the
  bundle inputs misses and rebuilds on its own runs (~5 minutes), which the derivation key tracks.
- 2026-11-07 (workspace, the cache policy's second run, all green): run #611 (`pull_request`, head
  `bc06a5b5`, the rebased tree; wall 16:55, 32 jobs — 26 success, 6 skipped, the five manual asset jobs
  and the weekly model-host probe — aggregate success at +16:54). Read from the cache API:
  `refs/pull/115/merge` holds 13 entries and **0** `gradle-*` (neither synchronize run wrote a Gradle
  entry), while `refs/heads/main` holds 7 `gradle-dependencies-v2`. The line is ticked on that evidence.
- 2026-11-07 (workspace, the bundle cache policy's evidence, pending its run): `refs/heads/main` holds
  **0** `stt-bundle-*` entries — the bundle was only ever saved under pull-request refs (this PR's ref
  holds the 387 MB entry from the old implicit save), so after the restore-only rule lands the first
  `main` run builds and saves the bundle once, and only then does a pull request with a matching
  derivation restore it; every pull request until that point rebuilds (~5 minutes).
- 2026-11-07 (workspace, the bundle cache policy's run, green after the re-run): run #612
  (`pull_request`, head `e5399d5a`, attempt 2 of 2) completed success — 32 jobs, all success or
  skipped, aggregate success at +9:52. Its first attempt failed only `Windows (e2e)` after 5:45, the
  guard firing on the starved VM (`docs/known-issues.md`'s row, not a run that counts against the
  change); the re-run of the failed job went green (9:42) and the whole run is green. The bundle job
  ran green (8:11). The cache API shows `refs/pull/115/merge` holding 7 entries — **0** `gradle-*` and
  the single 387 MB `stt-bundle-<derivation>` that run #611 saved at 08:19 under the old implicit
  save; run #612 **added no** `stt-bundle-*` entry, so the restore-only rule holds on a pull request.
  The repository read 11.10 GB at that moment (46 entries): the remaining overage is `main`'s own
  entries and the untouched PR entry — the writing that grew it has stopped on both caches.
- 2026-11-07 (workspace, in-review): the test list is fully ticked; the branch is
  `roadmap/85-ci-wall-time`, head `e5399d5a`, 47 commits over `main` (`bc54e5b5`, 0 behind), clean
  tree. Runs: #609 and #611 (the Gradle cache policy, 0 `gradle-*` under the PR ref, `main` holds 7
  `gradle-dependencies-v2`), #612 (the bundle cache policy, no new `stt-bundle-*` under the PR ref),
  #605–#607 (the levers' numbers). Local gates: 37/37 docs tests, `tsc`, Prettier, `actionlint`.
- 2026-11-07 (the root agent's review, applied at PR preparation): the discovered cache line's
  rationale was corrected to what the cache API measured — 11.56 GB total, `main` holding 9.7 GB
  (Nix 4.9 GB in two entries, Gradle 3.0 GB in 48) — so it now says PR-scoped growth stops rather
  than claiming the repository fits the Free plan's 10 GB; the overage is a `docs/known-issues.md`
  row naming the two Nix store caches as the next lever. The bundle-cache gating assertion no longer
  pins the save step's exact `if:` text (a reformat broke it); it checks that the save step exists,
  runs on `refs/heads/main`, and runs only on a miss. `docs/check-ci-gating.test.ts` 12/12.
