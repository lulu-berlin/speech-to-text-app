# Roadmap

Pending work, one file per item. Every item is specified, given a test list, and approved together with the user before any code is written (see [AGENTS.md § Workflow](../AGENTS.md#workflow)). Done items leave this folder: their spec and final test list move to `docs/specs/<area>.md`.

## Status legend

`draft` → `spec-approved` → `in-progress` → `in-review` → `done`

- `draft`: proposal written, not yet discussed with the user.
- `spec-approved`: proposal, open questions, and test list approved by the user (dated line in the item's Approval log).
- `in-progress`: being implemented in `workspaces/<NN-slug>` on branch `roadmap/<NN-slug>` (status set in the worktree's copy).
- `in-review`: full diff presented to the user.
- `done`: merged into `main`; file removed from `roadmap/`, its row links to the spec in `docs/specs/`.

## Dependency graph

```mermaid
graph TD
  01[01 dev environment ✓]:::done --> 02[02 repo skeleton and conventions ✓]:::done
  02 --> 03[03 database schema ✓]:::done
  03 --> 04[04 queue domain model ✓]:::done
  02 --> 05[05 process runner ✓]:::done
  05 --> 06[06 audio decoding and chunking ✓]:::done
  03 --> 06
  06 --> 07[07 whisper transcription ✓]:::done
  03 --> 07
  06 --> 08[08 speaker embeddings ✓]:::done
  08 --> 09[09 diarization ✓]:::done
  09 --> 10[10 output formats ✓]:::done
  04 --> 11[11 processing engine ✓]:::done
  05 --> 11
  07 --> 11
  09 --> 11
  03 --> 18[18 audio storage ✓]:::done
  18 --> 11
  20 --> 11
  11 --> 12[12 HTTP API and SSE ✓]:::done
  10 --> 12
  02 --> 13[13 frontend foundation ✓]:::done
  12 -. spec only .-> 13
  13 --> 14[14 frontend queue UI ✓]:::done
  13 --> 15[15 frontend history UI ✓]:::done
  02 --> 16[16 Nix flake package]
  14 --> 16
  15 --> 16
  17 --> 16
  20[20 cleanup refactors ✓]:::done
  12 --> 21[21 frontend-backend end-to-end ✓]:::done
  14 --> 21
  15 --> 21
  22 --> 21
  25 --> 21
  16 --> 19[19 Docker image and compose]
  21 --> 16
  09 --> 22[22 speaker rename ✓]:::done
  12 --> 22
  15 --> 22
  09 --> 23[23 faster clustering ✓]:::done
  13 --> 24[24 design system ✓]:::done
  24 --> 14
  24 --> 15
  24 --> 17
  24 --> 25[25 visual polish ✓]:::done
  14 --> 25
  15 --> 25
  11 --> 26[26 engine shutdown race ✓]:::done
  27[27 pin sqlite-jdbc ✓]:::done
  28[28 post-merge cleanup ✓]:::done
  29[29 backend bug fixes ✓]:::done
  30[30 diarization crash ✓]:::done
  17 --> 31[31 header and navigation polish ✓]:::done
  25 --> 31
  21 --> 31
  11 --> 32[32 settings: device selection]
  12 --> 32
  13 --> 32
  21 --> 32
  16 --> 33[33 Gradle bootstrap without Nix]
  34 --> 33
  34[34 dev mode ✓]:::done
  21 --> 35[35 README and architecture overview]
  36 --> 35[35 README and architecture overview]
  classDef done fill:#d4edda,stroke:#2e7d32
```

## Items

| Item | Status | Depends on |
| ---- | ------ | ---------- |
| [01 — Dev environment](../docs/specs/dev-environment.md) | done | — |
| [02 — Repo skeleton and conventions](../docs/specs/repo-skeleton-and-conventions.md) | done | 01 |
| [03 — Database schema](../docs/specs/database-schema.md) | done | 02 |
| [04 — Queue domain model](../docs/specs/queue-domain-model.md) | done | 03 |
| [05 — Process runner](../docs/specs/process-runner.md) | done | 02 |
| [06 — Audio decoding and chunking](../docs/specs/audio-decoding-and-chunking.md) | done | 03, 05 |
| [07 — Whisper transcription](../docs/specs/whisper-transcription.md) | done | 03, 06 |
| [08 — Speaker embeddings](../docs/specs/speaker-embeddings.md) | done | 06 |
| [09 — Diarization](../docs/specs/diarization.md) | done | 08 |
| [10 — Output formats](../docs/specs/output-formats.md) | done | 09 |
| [11 — Processing engine](../docs/specs/processing-engine.md) | done | 04, 05, 07, 09, 18, 20 |
| [12 — HTTP API and SSE](../docs/specs/http-api-and-sse.md) | done | 10, 11 |
| [13 — Frontend foundation](../docs/specs/frontend-foundation.md) | done | 02, 12 (spec-approved only) |
| [14 — Frontend queue UI](../docs/specs/frontend-queue-ui.md) | done | 13, 24 |
| [15 — Frontend history UI](../docs/specs/frontend-history-ui.md) | done | 13, 24 |
| [16 — Nix flake package](16-nix-flake-package.md) | spec-approved | 02, 14, 15, 17, 21 |
| [17 — Branding: stenographer typing animation](../docs/specs/branding-stenographer-animation.md) | done | 13, 24 |
| [18 — Audio storage: originals in the work directory, chunks as FLAC](../docs/specs/audio-storage.md) | done | 03 |
| [19 — Docker image and docker-compose setup](19-docker-image-and-compose.md) | spec-approved | 16 |
| [20 — Cleanup refactors](../docs/specs/cleanup-refactors.md) | done | — |
| [21 — Frontend–backend end-to-end check](../docs/specs/frontend-backend-end-to-end.md) | done | 12, 13, 14, 15, 22, 25 |
| [22 — Rename and merge a task's speakers](../docs/specs/speaker-rename.md) | done | 09, 10, 12, 15 |
| [23 — Faster speaker clustering](../docs/specs/faster-clustering.md) | done | 09 |
| [24 — Design system: tokens and primitives](../docs/specs/design-system.md) | done | 13 |
| [25 — Visual polish pass](../docs/specs/visual-polish.md) | done | 24, 14, 15 |
| [26 — Engine start/stop race (flaky DatabaseDependencySpec)](../docs/specs/engine-shutdown-race.md) | done | 11 |
| [27 — Pin the sqlite-jdbc version](../docs/specs/sqlite-jdbc-pin.md) | done | — |
| [28 — Post-merge cleanup refactors](../docs/specs/cleanup-refactors.md) | done | — |
| [29 — Post-merge bug fixes: engine wake, seek bar, audio join, SSE disconnect](../docs/specs/post-merge-bug-fixes.md) | done | — |
| [30 — Diarization crash on the user's machine (ONNX Runtime recursion)](../docs/specs/onnxruntime-pin.md) | done | — |
| [31 — Header and navigation polish](../docs/specs/header-and-navigation-polish.md) | done | 17, 25, 21 |
| [32 — Settings page: device, threads and upload defaults](32-settings-page-device-selection.md) | spec-approved | 11, 12, 13, 21 |
| [33 — Gradle bootstrap for developers without Nix](33-gradle-bootstrap-without-nix.md) | spec-approved | 16, 34 |
| [34 — Dev mode: frontend and backend with one command](../docs/specs/dev-mode.md) | done | — |
| [35 — README and architecture overview](35-readme-and-architecture-overview.md) | spec-approved | 21 |
| [36 — Follow mode stops by itself (hold-off race)](36-follow-mode-hold-off-race.md) | spec-approved | 15, 21 |
