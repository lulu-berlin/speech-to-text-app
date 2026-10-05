# 01 — Dev environment

Status: in-review
Depends on: —

## Goal

Make `devenv.nix` provide every tool and model the app needs during development (Node/npm for the frontend, JDK/Gradle for the backend, whisper-cli, ffmpeg, SQLite, both models), and remove the devenv template boilerplate.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): dependencies come from devenv; Nix flake is the deliverable.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): no Python toolchain is added.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): speaker model URL and sha256; ONNX Runtime Java.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): whisper model URL and sha256.
- [ADR 0010 — Development toolchain in devenv](../docs/decisions/0010-development-toolchain.md): agents add needed tools to `devenv.nix` directly; devenv's default Node; global `tsc` kept.

## Proposal

Done on 2026-10-05 by standing user instruction ("whichever tool you need for development, add it to devenv.nix"), pending the user's shell check:

- `languages.javascript.enable = true;` and `languages.javascript.npm.enable = true;` (node + npm, devenv's default Node package from the pinned nixpkgs; ADR 0010).
- Kept `languages.kotlin` (JDK 21, Gradle), `languages.typescript` (global `tsc`; ADR 0010), `git`, the `whisper-cpp` Vulkan override, `ffmpeg`, `vulkan-tools`, `sqlite`.
- Added `curl` and `jq` for manual API/SSE smoke checks.
- ONNX Runtime: no Nix change needed. The native library bundled in `com.microsoft.onnxruntime:onnxruntime` 1.30.0 loads under the devenv JDK without `LD_LIBRARY_PATH` (the JVM already maps libstdc++); a session on the CAM++ model was created successfully in the agent sandbox. `pkgs.onnxruntime` + `-Donnxruntime.native.path` stays the fallback if it ever fails on the user's machine.
- `nix/models.nix`: `fetchurl` of both models (hashes from ADR 0003 / ADR 0008, re-checked 2026-10-05 against the download / HF `x-linked-etag`), imported by `devenv.nix`, exported as `STT_WHISPER_MODEL` and `STT_SPEAKER_MODEL`. Reused by the flake in item 16.

Remaining:

- Remove template boilerplate in `devenv.nix`: `env.GREET`, `scripts.hello`, the `enterShell` greeting, the `enterTest` git-version check.
- Replace `enterTest` with the test list below as automated checks, run by `devenv test`. The expected model hashes are written out in the test, independent of `nix/models.nix`.

Nix evaluation cannot run inside the agent sandbox; `devenv test` red/green runs are done by the user.

## Open questions for the user

None open. (Node version and global `tsc` were decided in ADR 0010.)

## Test list (written with the user; Canon TDD step 1)

Each item is one check in `enterTest` (`devenv test`), except where marked manual.

- [ ] The shell provides `node` and `npm`
- [ ] The shell provides a JDK 21 `java`, `kotlin`, and `gradle`
- [ ] The shell provides `whisper-cli`, `ffmpeg`, `sqlite3`, `curl`, and `jq`
- [ ] `STT_WHISPER_MODEL` points to a file whose sha256 matches ADR 0008
- [ ] `STT_SPEAKER_MODEL` points to a file whose sha256 matches ADR 0003
- [ ] Entering the shell prints no template greeting (`hello from devenv`) — manual: `devenv shell -- true`

Moved to item 08: "The ONNX Runtime native library loads from the JVM inside the shell" (needs the backend build).

Agent sandbox run, 2026-10-05 (`enterTest` body executed with bash, no Nix): red `FAILED: node and npm` (no node in the sandbox); with stub `node`/`npm` on PATH and the real downloaded models all five checks print `ok`; with the speaker model passed as `STT_WHISPER_MODEL` the whisper hash check fails. Items are ticked only after the user's `devenv test` run.

## Acceptance criteria

- User runs `devenv test` in `workspaces/01-dev-environment`: all checks pass.
- `devenv shell -- true` prints no `hello from devenv`.
- `devenv.nix` contains no `env.GREET`, `scripts.hello`, `enterShell` greeting, or `enterTest` git check.
- `nix/models.nix` exists and is imported by `devenv.nix`; hashes match ADR 0003 and ADR 0008.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-05: user instructed agents to add every needed development tool to `devenv.nix` directly; node/npm, curl, jq, and `nix/models.nix` added under that instruction.
- 2026-10-05: user accepted devenv's default Node and keeping the global `tsc`; recorded as ADR 0010.
- 2026-10-05: user asked to finish item 01; proposal accepted as written, test list finalized by the agent (seed + `curl`/`jq`, ONNX check moved to item 08) and presented to the user with the diff for review.
