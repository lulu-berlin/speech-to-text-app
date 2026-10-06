# 0023 — Persistence may import feature-owned value types

Status: accepted · Date: 2026-10-06

## Context
Roadmap item 04 introduces the `stt/queue` domain feature: a pure Kotlin state machine for task status, independent of I/O. The status type `TaskStatus` (`QUEUED`, `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`, `CANCELED`) was first created in item 03 inside `stt/db`, where Exposed persists it into `tasks.status`. Item 04 needs exactly this type as the state machine's input, and the domain must not import `stt/db` (persistence would make it impure). The backend conventions ([backend.md](../conventions/backend.md), ADR 0013) say a feature owns its types and forbid cross-feature imports except through explicit interfaces — leaving unclear whether the persistence feature may depend on a value type owned by another feature.

## Decision
- `TaskStatus` moves to `stt/queue` — the feature whose semantics it models (queue lifecycle state). `stt/db` imports it from there for the `tasks.status` column.
- The persistence feature (`stt/db`) **may import value types (enums and data classes persisted verbatim) from the feature that owns them**. This is a read-only dependency on a type definition, not behavioral coupling, and does not violate the "explicit interfaces" rule.
- The reverse direction stays forbidden: `stt/queue` and all other features must never import `stt/db`.

## Consequences
- One `TaskStatus` shared by the queue state machine and the database; no mapping layer duplicates the enum.
- Item 03's `TaskStatus` moves home as a small mechanical refactor; `TaskPhase` stays in `stt/db` until a feature that owns transcription progress claims it.
- `stt/queue` stays free of db/Ktor/coroutines (item 04 acceptance criterion).
- Future persistence of other feature-owned value types follows the same rule without a new ADR.