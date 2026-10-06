# 0021 — Integration tests: Kotest tags and `gradle integrationTest`

Status: accepted · Date: 2026-10-06

## Context
Roadmap item 05 introduces the first tests that run real processes (`sh`, `sleep`, `seq`, `setsid`). They must not slow down or destabilize the default verification command, and they depend on dev-environment tooling. AGENTS.md § Testing promised: "Tests that need real ffmpeg, whisper-cli, or ONNX models are tagged `Integration`; they are run with Kotest tags when they exist (command to be pinned by the item that adds them)."

## Decision
- Process-based specs are tagged with a Kotest `Tag("Integration")`.
- `gradle test` — and therefore `gradle check` — excludes the tag (`kotest.tags=!Integration`): the default verification command stays a fast unit run that spawns no real processes.
- A dedicated task `gradle integrationTest` (verification group) includes only the tag (`kotest.tags=Integration`).
- Integration tests are deterministic: no fixed sleeps used as synchronization; bounded waits and timeouts; every test cleans up its processes (including on failure); the suite leaves no process behind.
- The exact commands are pinned in AGENTS.md § Testing / § Commands.

## Consequences
- `gradle check` alone is not the full test run; both tasks are documented so CI or a pre-merge run can invoke them.
- Integration runs need the dev tooling from `devenv.nix` (util-linux for `setsid`, coreutils/bash for the counters and `sleep`).
- The Gradle wiring (the `kotest.tags` expression — `!Integration` on `test`, `Integration` on `integrationTest` — as system properties on the respective Test tasks) was verified empirically before pinning.