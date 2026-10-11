# 0043 — ONNX Runtime is pinned like sqlite-jdbc

Status: accepted, applies 0042's force rule to onnxruntime · Date: 2026-10-11

## Context

The diarization session uses `com.microsoft.onnxruntime:onnxruntime`. On the user's machine (AMD Ryzen,
16 threads, CPU family 25) the first inference segfaults: a SIGSEGV from a native stack overflow inside
`libonnxruntime.so` — a tight three-address recursion cycle repeated for hundreds of frames (cores
2026-10-10 22:xx through 2026-10-11 03:xx), with no JVM `hs_err` because the fault sits in ORT's own
native thread. The sandbox (Ryzen 9 4900H, Zen 2, 8 visible threads) runs the identical pipeline to
completion, so the bug is ORT × machine, not application code.

### Version ladder (all measured)

| Version | Embeddings (sandbox Integration suite) | Crash on user's machine |
| --- | --- | --- |
| 1.26.0 | broken — suite red, byte-identical numbers to 1.27.0 | not tested (moot) |
| 1.27.0 | broken — suite red | not tested (moot, below) |
| 1.28.0 | broken — suite red, byte-identical numbers to 1.27.0 | not tested (moot) |
| 1.29.0 | good — suite green | yes, exit 139 |
| 1.30.0 | good — suite green | yes, exit 139 |

The embedding breakage is gross, not borderline: same-speaker cosine 0.2427 falls below a
different-speaker cosine 0.4376, 63 of 72 same-speaker pairs land above the 0.5448 threshold
(ADR 0032), and auto mode invents a third speaker. No re-tuning can repair destroyed ordering, so
1.26.0–1.28.0 are disqualified on diarization quality alone — the pin must come from the
embedding-competent line, which is exactly the crashing line. No ORT version in 1.26.0–1.30.0
satisfies both contracts; the computation line that yields the app's embeddings starts at 1.29.0. Diagnostics that do NOT stop the crash: sequential execution with one
inter-op thread, `STT_THREADS=1`, `-Xmx1g`, `-Xint`.

### Mechanism (disassembled from the 1.30.0 Maven `.so`)

The coredump cycle `+0xf37493 → +0xf3c215 → +0xf374ae → +0xf37493` is a mutual recursion between two
functions: a recursive, tag-switched node-traversal dispatcher (`0xf36f80`; the 13-case table lives at
`0x16add80`) and a guarded re-entry helper (`0xf3c190`) that marks a node in-progress
(`movl $1,0x8(%rbx)`), recurses, restores, and terminates cycles by refcount check
(`cmp $1,%ebp; jg ret` at `0xf3c220`). The 1.29.0 core shows the same pattern at shifted addresses
(`0xef5b13 → 0xefa855 → 0xef5b2e`, 852 frames). Error-path strings are inlined `libstdc++`
bounds-check aborts (`stl_vector.h`, `__n < this->size()`), so ORT ships with stdlib debug assertions
inlined; the traversal operates on a plain `std::vector` of 16-byte node records
`{pointer, refcount}`. Infinite recursion means the per-node guards see inconsistent state on the
user's CPU — the signature of a CPU-dispatched kernel corrupting adjacent memory earlier in the same
run (which also explains why scheduling options, JIT off, and heap size are all irrelevant). The
leading discriminator between the two machines is CPU feature dispatch: Zen 2 never selects AVX-512
paths; a family-25 Zen 4/5 does. Pending confirmation: the user's exact CPU model/flags and a
`clearcpuid` experiment.

## Decision

ONNX Runtime is force-pinned at the catalog version exactly like sqlite-jdbc (ADR 0042):
`resolutionStrategy.force(libs.onnxruntime)` in `backend/build.gradle.kts`, with the version in
`gradle/libs.versions.toml` — the catalog stays the single source of truth. A bump is a one-line catalog
change plus: `backend/deps.json` regeneration (the nixpkgs Gradle lockfile, ADR 0014/0016), the full
Integration suite on `gradle integrationTest` (real CAM++ and whisper models), and a real-model
diarization run on the user's machine (the crash is machine-specific; the sandbox cannot verify it).

As of 2026-10-11 the pin stands at 1.29.0 because it is the newest version with green embedding
behavior; the item is NOT resolved (1.29.0 still crashes on the user's machine). `SpeakerModel.
sessionOptions` additionally runs sequential with one inter-op thread as defense-in-depth: ORT's
parallel inter-op scheduler is never entered, at no cost to this single-forward-pass model.

## Consequences

- No transitive can pull ORT back up to a higher version; `gradle dependencyInsight` shows the catalog
  version marked `(forced)`.
- The pin stays at the newest embedding-green version until a release without the recursion has been
  verified on the user's machine. Upgrading is deliberate and verified on both machines (embedding
  behavior in the sandbox, crash on the user's machine).
- The resolution, the catalog, and the runtime library agree; `backend/deps.json` changes with each
  bump.

## Open

The actual fix is still open: likely build-level (an ONNX Runtime built with the offending CPU path
disabled) or upstream (microsoft/onnxruntime bug report with the offset evidence). The version pin is
the containment measure that keeps the dependency reproducible while that is decided — and it is the
mechanism that will carry the fix when a good version exists.