# 0022 — Kotlin compiler runs inside the Gradle daemon

Status: accepted · Date: 2026-10-06

## Context
During roadmap item 05, `gradle integrationTest` occasionally stalled because the development VM ran out of memory (memory pressure stalls, one OOM-killed Gradle daemon). By default the Kotlin Gradle plugin starts a separate Kotlin compile daemon beside the Gradle daemon. Peak RSS during `gradle check --rerun-tasks`, measured on 2026-10-06:

| setup | Gradle daemon | Kotlin daemon | test JVM | wall time |
|---|---|---|---|---|
| default (Kotlin daemon) | 802 MB | 622 MB | 189 MB | 82 s (cold) |
| `in-process` | 849 MB | — | 191 MB | 29 s |

Gradle 8 already caps the daemon and test JVM heaps at 512 MB. Lowering the daemon cap to 384 MB with 256 MB metaspace gave no RSS gain (909 MB): metaspace (about 168 MB used) and the code cache dominate, and the heap held about 200 MB after the build.

## Decision
- `backend/gradle.properties` sets `kotlin.compiler.execution.strategy=in-process`: Kotlin compiles inside the Gradle daemon, and no Kotlin daemon is started.
- No explicit heap caps (`org.gradle.jvmargs`, `maxHeapSize`): Gradle's defaults stay in force, since lower caps measured no gain and add OOM risk as the code grows.

## Consequences
- A development build uses about 620 MB less memory; incremental compilation still works.
- The setting also applies to the Nix build (item 16), where a separate daemon is useless anyway.
- If the Gradle daemon runs out of heap during compilation as the codebase grows, raise `org.gradle.jvmargs` in a new ADR.
