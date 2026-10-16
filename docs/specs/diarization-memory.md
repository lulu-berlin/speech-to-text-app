# 43 — Diarization memory: stream speaker windows from the stored clip

Status: done (roadmap item 43, approved 2026-10-16)
Depends on: [11](processing-engine.md) (done; `clipPcm` lives in the engine)
Blocks: —

## Goal

Diarizing a long recording must not reconstruct the full PCM clip. It streams speaker windows from the stored canonical clip while preserving global timestamps and producing the same speakers and segments as before. Peak incremental JVM heap during diarization stays within one whole-clip PCM FloatArray size above the post-model-load baseline; measure it on a long recording.

## Already decided

- Reported 2026-10-12 by a third-party source review of `main`, triaged by the root agent against the code (the finding holds).
- Current evidence (after item 44): `ProcessingEngine.clipPcm()` materializes the whole canonical clip, decodes the whole PCM to a file, reads that file into a full-size `byte[]`, then copies it into a full-size `FloatArray`. `Embeddings.embed()` holds that array and creates per-segment windows from it. At 16 kHz mono float, one PCM representation is ~115 MB per 30 min (~461 MB for 2 h), before the ONNX session; simultaneous full-size representations multiply that peak.
- The user decided to include direct speaker-window streaming from the stored clip in this item rather than leave it as a follow-on. Windowing must preserve global timestamps so embedding windows and clustering output remain identical.

## Proposal

Stream the speaker windows from the stored canonical clip into diarization instead of materializing the full clip as a PCM `FloatArray`. Preserve global sample/timestamp positions across windows so speaker embeddings and clustering remain behaviorally unchanged. Measure peak incremental JVM heap above the post-model-load baseline on a long clip.

## Decisions (consultation, 2026-10-15)

- Stream speaker windows from the stored canonical clip in item 43; do not split this into a later item.
- For the long-clip memory check, peak incremental JVM heap above the post-model-load baseline is bounded by one whole-clip FloatArray (`sampleCount × 4` bytes).
## Test list (approved 2026-10-15; behavioral scenarios)

- [x] Diarizing a multi-window clip produces the expected speaker assignments, segments and timestamps, including speaker windows that cross chunk/window boundaries.
- [x] A recording of at least 20 minutes diarizes completely with the expected output; peak incremental JVM heap above the post-model-load baseline is no more than one whole-clip FloatArray (`sampleCount × 4` bytes), and the clip length and measured peak are recorded on the user's machine.
- [x] (must not break) `ProcessingEngineDiarizationSpec` and the clustering suites stay green.
- [x] (discovered) Every segment of that recording, streamed from the stored clip, gets exactly the samples the whole-clip PCM holds at its timestamps: the streamed window equals the oracle's windowing over the whole clip, sample for sample, for windows that start and end inside a stored one-second row.
- [x] (discovered) A segment longer than 20 s that ends past the clip's end gets the oracle's clamped center-capped window instead of failing the task.

## Acceptance criteria

- Diarization streams speaker windows from the stored canonical clip rather than reconstructing whole-clip PCM; expected speakers, segments and global timestamps are unchanged.
- On a recording of at least 20 minutes, the measured peak incremental JVM heap above the post-model-load baseline is no more than one whole-clip FloatArray (`sampleCount × 4` bytes).
- All test-list items ticked and green; user approved the diff.


## Measurement notes

The 20-minute memory criterion is measured as the **live set**, not as sampled heap usage. With
`-Xlog:gc:file=…`, the heap used **after each collection** is the live data at that instant: the figure is the
largest after-GC value during DIARIZING minus the post-model-load baseline, where the baseline is the after-GC
value once a short clip has loaded the model (a fresh JVM loads the speaker model on its first diarization, so
the long run's own JVM has no pre-diarization post-model-load sample; its end-of-run idle value with the model
loaded sits alongside it). Sampling used heap (`jcmd GC.heap_info`, or any poll of used heap) cannot attest a
live-heap bound: it counts garbage not yet collected — on the measurement below it peaked 220.8 MB above a
19.4 MB baseline for a run whose live incremental was 8 MB, because G1 fills its young generation (about 60 % of
the heap) before a young collection.

Measured 2026-10-16 on the user's machine, 1199.995438 s of 16 kHz mono speech (`sampleCount` 19 199 927, bound
= `sampleCount × 4` = 76 799 708 B = 73.2 MiB): post-model-load baseline 15 MB, diarization of the clip 20–23 MB
after GC, incremental 8 MB — a ~9× margin — with the same speaker output in two runs (four speakers, 65/44/14/4
segments). The same run's chunking phase (item 44's encode path, right after upload) reached 100 MB after GC,
and a 1200 s fixture reproduced it at 110 MB in the sandbox: that is outside this item's phase and lives in
`docs/known-issues.md` (CHUNKING holds the whole canonical clip two to three times).

## Approval log

- 2026-10-12: filed from the third-party review's finding 4 after code triage (multi-copy rebuild path; the reviewer's math confirmed).
- 2026-10-15: consultation — the user chose to stream speaker windows from the stored canonical clip in this item, not defer it. Test list approved: output parity across window boundaries; a 20-minute-or-longer clip with an incremental JVM heap limit of one whole-clip FloatArray above the post-model-load baseline, measured on the user's machine; existing diarization and clustering suites stay green. The structural allocation check was removed from the test list because it is not a behavioral scenario. Status: spec-approved.

- 2026-10-16: test-list item 1 green. Red (`gradle integrationTest --tests stt.diarization.StreamedDiarizationIntegrationSpec`): `compileTestKotlin` — `Unresolved reference 'ClipPcmSource'` at `StreamedDiarizationIntegrationSpec.kt:24,104` (the streaming seam did not exist). Green: `gradle check` (ktlint + unit tests) and `gradle integrationTest` (132 specs, 0 failures, including the new spec, the adapted `EmbeddingsSpec`, `LibriSpeechDiarizationSpec` and `SpeakerThresholdSpec`, and `ProcessingEngineCanonicalStorageSpec`, which runs the real pipeline and diarizes an 11 s clip through the streamed source). New: `StreamedDiarizationIntegrationSpec` — speakers 1089/1188/1089 concatenated and stored as a canonical clip, six segments meeting at the clip boundaries (each window starts and ends mid-row and spans several one-second rows); the stored assignments are `[0, 0, 1, 1, 0, 0]`, the timestamps are unchanged and the turns change at the clip boundaries. Implementation: `stt.diarization.PcmSource` (a clip by global sample range), `stt.engine.ClipPcmSource` (the stored clip's rows for the range, decoded and read by position; both scratch files removed on every path), `EmbeddingWindows`/`Embeddings`/`Diarizer`/`SpeakerDiarizer` take the source instead of a whole-clip `FloatArray`, and `ProcessingEngine.clipPcm` (whole-clip materialization) is gone; the clip's sample count is the stored header's STREAMINFO (`stt.audio.streamInfoTotalSamples`), so a segment near or past the clip's end windows as it did.

- 2026-10-16: (discovered) test-list item green, approved by the user at the item-1 checkpoint. It has no red of its own — the item-1 cycle's implementation already satisfies it — so it was shown to be meaningful instead: with the reference window deliberately shifted by 1 s the scenario fails, naming the first differing sample (`segment 0..3312 ms; sample 0 of 52992 is 3.6621094E-4, expected 0.11843872`), and the windows it compares are 52 992–123 760-sample slices of the clip, never padding. Green: `gradle check` and `gradle integrationTest --tests stt.diarization.StreamedDiarizationIntegrationSpec` (2 tests, 0 failures). Implementation: the second scenario in `StreamedDiarizationIntegrationSpec` streams each of the six segments' windows from the stored clip and compares them, sample for sample, with the same windowing over the whole-clip PCM the fixture holds (`pcmSource(recording.pcm)`); the fixture's claims (five of six segments start inside a stored row, every window is a slice) are asserted with it.

- 2026-10-16: (must not break) item green, on the same sources as the two cycles above (no further code change): `gradle check` — ktlint clean and 109 unit spec classes, 0 failures, 0 errors, `ProcessingEngineDiarizationSpec` 6 tests, `ClusteringSpec` 13, `SpeakerIdsSpec` 7, `EmbeddingWindowsSpec` 3; `gradle integrationTest` — 132 spec classes, `failures="0" errors="0"`, including `ClusteringHeapIntegrationSpec` (item 23's heap-bounded child JVM) and `StreamedDiarizationIntegrationSpec` (2 tests).

- 2026-10-16: test-list item 2 green, measured on the user's machine on the branch (`a0224da`). Clip: `~/Recordings/jackson.webm` looped to 1199.995438 s at 16 kHz mono → 19 199 927 samples → bound `sampleCount × 4` = 76 799 708 B = 73.2 MiB. Method: the installed server with `-Xmx512m` and `-Xlog:gc:file=…`, plus `jcmd GC.heap_info` sampled every 0.2 s as a cross-check; the number that counts is the live set — the heap after each GC — because the sampled `used` includes uncollected garbage (it peaked 220.8 MB above a 19.4 MB idle baseline, i.e. G1 filling its young generation, not retention). Post-model-load baseline (after-GC, short-clip run): 15 MB. Diarization of the 20-minute clip (after-GC, JVM uptime 299–321 s): 20–23 MB. **Incremental: 8 MB ≤ 73.2 MiB → pass, ~9× margin.** The run itself: task COMPLETED with four speakers, cluster sizes 65/44/14/4 and within-cluster cosines 0.738/0.616/0.656/0.697 — identical in two independent runs, so the expected output is deterministic. The same log's ~100 MB after-GC peak sits at uptime 25.8 s, right after upload: CHUNKING (item 44's encode path), already filed as `docs/known-issues.md:32` (LOW), so nothing new was filed and this item's phase is unaffected; the sandbox reproduced it independently (110 MB after-GC within 10 s of uptime for a 1200 s 16 kHz fixture). At merge, `docs/known-issues.md:21`'s "43 diarization memory (transient PCM copies)" clause is stale: this item handles it, and the roadmap file moves to `docs/specs/`.

- 2026-10-16: (discovered) test-list item green, filed by the root agent's review of `6c735ad4` as the branch's one behavior change against `main`: `EmbeddingWindows.kt`'s capped branch clamps the slice with the clip's sample count (`en = minOf(t1, total, st + CAP_WINDOW_SAMPLES)`), where `main`'s `copyOfRange` threw `IndexOutOfBoundsException` and failed the task; the oracle slices `pcm[st:en]` and therefore clamps (`docs/oracle/transcribe.py:244-248`). The branch already behaved correctly, so there was no natural red: meaningfulness was shown by mutation — with `total` dropped from that `minOf` (main's form) exactly the new case fails with `java.lang.IndexOutOfBoundsException: toIndex (480000) is greater than size (400000).` (`EmbeddingWindowsSpec` 1 of 4 failed, the other three green), and restoring it returns the spec to 4/4. Case: "a 25 s PCM and a 40 s segment that ends past the clip's end" — `pcm[i] = i`, 400 000 samples, segment `[0, 40 000 ms]`: `dur` = 400 000 > cap, `st` = 160 000, `en` = min(640 000, 400 000, 480 000) = 400 000 → 240 000 samples, `window[0]` = 160 000f, `window[239 999]` = 399 999f — the samples the oracle's clamped slice gives. Final runs on this head: `gradle check` green (110 unit spec classes, 0 failures, 0 errors) and `gradle integrationTest --tests 'stt.diarization.*'` green (14 spec classes, 0 failures, 0 errors: `EmbeddingsSpec` 7, `LibriSpeechDiarizationSpec` 1, `SpeakerThresholdSpec` 1, `StreamedDiarizationIntegrationSpec` 2, `ClusteringHeapIntegrationSpec` 2; `EmbeddingWindowsSpec` is untagged, so its 4 ran in `gradle check`). The living docs were updated in `a4e9ee5e` (item 44/43 wording in `processing-engine`, `speaker-embeddings`, `diarization`, `PcmDecoder`). Status stays `in-review`.

- 2026-10-16: review changes done. (2) The living docs describe item 43's streamed windows in `a4e9ee5e` (`processing-engine`, `speaker-embeddings`, `diarization`, `PcmDecoder`'s KDoc; `gradle ktlintCheck` and `node docs/check-links.mjs` over the three specs green); ADR 0024 and done items' logs untouched. (1) The (discovered) clamped-window case is green with its mutation evidence (entry above). Refactor, both approved options: `docs/check-architecture-diagram.mjs`'s `Engine->Files` evidence dropped the dead `ScratchDir.clipFile` alternative (this item removed that method) and now names `ClipPcmSource.kt`'s `diarizeWindow`/`diarizePcm` calls — live evidence, verified by a deliberately non-matching pattern failing the check (`arrow Engine->Files: … does not match`, exit 1) before restoring it; `node docs/check-architecture-names.mjs` and `check-architecture-diagram.mjs` green (168 names, 11 boxes, 10 arrows). Rebased onto `main` `cc4663dc` (item 55's spec move and README index): clean, 13 commits replayed, head `67ef2e97`, and the rebase changed nothing of this item — `git diff 1a5b35c8 67ef2e97` is main's `roadmap/README.md` and `docs/specs/atomic-transitions-lock-probe.md` only. Re-verified on the new base: `node docs/check-links.mjs README.md docs/architecture.md` plus the three specs green, `gradle check` green (110 unit spec classes, 0 failures, 0 errors), `gradle integrationTest --tests 'stt.diarization.*'` green (14 spec classes, 0 failures, 0 errors) — Gradle reported both up-to-date because the rebase changed no input content. Status: `in-review`.

- 2026-10-16: root-agent re-review of `915bc11a`: both review changes and the approved refactor verified (`gradle check` 305 tests, 0 failures; `gradle integrationTest` for `stt.diarization.*` + `ProcessingEngineCanonicalStorageSpec` 51 tests, 0 failures; doc checks green). The user approved the diff and one more line: `docs/specs/processing-engine.md`'s "Since item 44" note now says its numbered step 3 is updated for item 43. Spec and final test list moved to `docs/specs/diarization-memory.md`; status done.