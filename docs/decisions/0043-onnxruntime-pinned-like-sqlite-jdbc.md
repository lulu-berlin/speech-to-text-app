# 0043 — ONNX Runtime is pinned like sqlite-jdbc

Status: accepted, applies 0042's force rule to onnxruntime · Date: 2026-10-11

## Context

The diarization session uses `com.microsoft.onnxruntime:onnxruntime`, upgraded to 1.30.0 with the catalog.
On the user's machine (AMD Ryzen, 16 threads) the first inference segfaulted every time: a SIGSEGV from a
native stack overflow inside `libonnxruntime.so` — a tight three-function recursion cycle, identical across
runs, with no JVM `hs_err` because the fault sits in ORT's own native thread. The sandbox (different CPU)
runs the same pipeline to completion, so it is an ORT-version × machine-dispatch bug, not application code.
Session options cannot dodge it: the crash reproduced byte-identically with sequential execution, a single
inter-op thread, `STT_THREADS=1`, `-Xmx1g`, and `-Xint`. The only 1.29.x release is 1.29.0, the last version
before the regression window (1.29.0 → 1.30.0).

## Decision

ONNX Runtime is force-pinned at the catalog version exactly like sqlite-jdbc (ADR 0042):
`resolutionStrategy.force(libs.onnxruntime)` in `backend/build.gradle.kts`, with the version in
`gradle/libs.versions.toml` — the catalog stays the single source of truth. A bump is a one-line catalog
change plus:

- `backend/deps.json` regeneration (the nixpkgs Gradle lockfile, ADR 0014/0016);
- the full Integration suite on `gradle integrationTest` (real CAM++ and whisper models);
- a real-model diarization run on the user's machine — the crash is machine-specific, the sandbox cannot
  reproduce or verify it.

`SpeakerModel.sessionOptions` additionally runs sequential with one inter-op thread (roadmap item 30) as
defense-in-depth: ORT's parallel inter-op scheduler is never entered, at no cost to this
single-forward-pass model.

## Consequences

- No transitive can pull ORT back to 1.30.0+; `gradle dependencyInsight` shows the catalog version
  marked `(forced)`.
- ONNX Runtime stays on 1.29.x until a release with the dispatch fix has been verified on the user's
  machine; upgrading is deliberate and verified on both machines.
- The resolution, the catalog, and the runtime library agree; `backend/deps.json` changes with each bump.