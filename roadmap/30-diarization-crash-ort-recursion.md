# 30 — Diarization crash on the user's machine (ONNX Runtime recursion)

Status: draft
Depends on: —

## Goal

Make speaker diarization reliable on the user's machine: the first ONNX Runtime inference segfaults there (and only there) with a repeating native stack. Eliminate the broken ORT code path — first by session options, then by a version pin if needed.

## Evidence (2026-10-10/11)

- Every end-to-end run that reaches diarization dies with exit 139: fresh DB runs (upload → transcribe → DIARIZING), leftover-task resume runs (seconds after startup), and `-Xint` (no JIT) runs. `STT_THREADS=1` did not help; `-Xmx1g` did not help (27 GiB RAM, 16 Gi available — not memory).
- No `hs_err`, no JVM fatal banner; `coredumpctl` shows a real SIGSEGV + core for every crash.
- `coredumpctl info` backtrace (core 1425179): every frame is inside `libonnxruntime.so` (extracted from the Maven jar to `/tmp/onnxruntime-java*/`), a **tight three-address cycle repeated across hundreds of frames** (`+0xf37493 → +0xf3c215 → +0xf374ae → +0xf37493 …`), fault on the stack guard → **native stack overflow from infinite recursion in ONNX Runtime 1.30.0's execution path**, hit by the CAM++ model's inference on this machine (AMD Ryzen, 16 threads).
- The sandbox (different CPU) runs the identical pipeline to completion — CAM++ loads, embeddings and clustering finish — so the bug is ORT-version × machine dispatch, not our code.
- ORT is the `com.microsoft.onnxruntime:onnxruntime:1.30.0` Maven artifact (bundled natives; the `SpeakerModel.sessionOptions` only sets intra-op threads when `STT_THREADS` is given — execution mode and inter-op threading are ORT defaults).

## Proposal

To be consulted with the user (2026-10-11): hypothesis first, version pin as fallback. Steps, each verified by the user on their machine (the sandbox cannot reproduce):

1. **Session options (cycle 1)**: `SpeakerModel.sessionOptions` sets sequential execution (`ExecutionMode.ORT_SEQUENTIAL`) and a bounded inter-op thread count, so the parallel executor — the recursion's home — is never entered. Sandbox-verifiable that the suite stays green; user-verified that diarization completes.
2. **Version pin (cycle 2, if 1 fails)**: pin ONNX Runtime to an earlier stable (e.g. 1.29.x or 1.27.x) in the version catalog, `deps.json` regenerated with the user, suites green — mirroring the sqlite-jdbc item (ADR 0042 is the precedent for the pin rule; the version itself is an ORT fix for the recursion).
3. If neither works: capture the native symbols (unstrip the jar's `.so`, `nm`/`addr2line` the three offsets) and report the exact function names upstream.

## Open questions

- Which session options exactly (execution mode; intra/inter thread caps)?
- Version fallback target?

## Test list (written with the user; Canon TDD step 1)

To be written with the user. Expected scope:

- Session options set as decided; the engine's Integration suite (real CAM++ model) stays green in the sandbox.
- The user's full run (upload → transcription → diarization → COMPLETED, twice) on their machine.
- The version-pin path, if needed: `dependencyInsight` shows the pinned ORT; suites green; `deps.json` regenerated with the user.

## Acceptance criteria

- Speaker diarization completes on the user's machine (a full upload-to-COMPLETED run with speakers on).
- `gradle check` and `gradle integrationTest` green (sandbox and user).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's crash evidence (`coredumpctl info` backtrace showing the ORT recursion).