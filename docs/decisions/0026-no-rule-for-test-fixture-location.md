# 0026 — No rule for where test fixtures live

Status: accepted · Date: 2026-10-09

## Context
The backend conventions (roadmap item 02, [docs/conventions/backend.md](../conventions/backend.md)) said "never fixtures committed to the repo", so every external test file was fetched by URL + sha256 through Nix (`nix/test-fixtures.nix`, exported as `TEST_*` by `devenv.nix`). For roadmap item 08 that meant downloading the whole 346 MB LibriSpeech `test-clean` archive, which is only distributed as one file, into the Nix store to extract three FLAC clips of 400 KB in total. The user rejected the rule on 2026-10-09.

## Decision
- The convention is removed; there is no rule for where test fixtures live.
- In practice: a fixture that can easily be added as a Nix input is fetched by hash through Nix; otherwise it is committed to the repository.

## Consequences
- Item 08's LibriSpeech clips are committed under `backend/src/test/resources/librispeech/` and read from the test classpath; their Nix fetch and `TEST_LIBRISPEECH_CLIPS` are dropped. Item 07's `jfk.wav` and `ggml-tiny.en` model stay Nix inputs.
- A committed third-party file must be licensed for redistribution and carry what its licence requires (for LibriSpeech, CC BY 4.0: the licence notice next to the clips and the entry in [docs/attributions.md](../attributions.md)).
- Committed fixtures grow the Git history permanently.
