# Dev environment

Status: done (roadmap item 01, merged 2026-10-05)

## Goal

`devenv.nix` provides every tool and model the app needs during development (Node/npm for the frontend, JDK/Gradle for the backend, whisper-cli, ffmpeg, SQLite, both models), without devenv template boilerplate.

## Decisions

- [ADR 0001 — Stack and monorepo](../decisions/0001-stack-and-monorepo.md): dependencies come from devenv; Nix flake is the deliverable.
- [ADR 0002 — No Python, oracle only](../decisions/0002-no-python-oracle-only.md): no Python toolchain.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../decisions/0003-kotlin-diarization-campplus-onnx.md): speaker model URL and sha256; ONNX Runtime Java.
- [ADR 0008 — Default whisper model](../decisions/0008-default-whisper-model.md): whisper model URL and sha256.
- [ADR 0010 — Development toolchain in devenv](../decisions/0010-development-toolchain.md): agents add needed tools to `devenv.nix` directly; devenv's default Node; global `tsc` kept.

## Specification

- `languages.javascript.enable` + `languages.javascript.npm.enable`: node + npm (devenv's default Node package from the pinned nixpkgs).
- `languages.kotlin` (JDK 21, Gradle), `languages.typescript` (global `tsc`), packages `git`, `whisper-cpp` with `vulkanSupport = true`, `ffmpeg`, `vulkan-tools`, `sqlite`, `curl`, `jq` (manual API/SSE smoke checks).
- ONNX Runtime: no Nix setup. The native library bundled in `com.microsoft.onnxruntime:onnxruntime` (pinned to 1.29.0, ADR 0043) loads under the devenv JDK without `LD_LIBRARY_PATH`, because the JVM already maps libstdc++ (loading verified on 1.30.0 in 2026-10-05, re-verified on the pin by the Integration suite). Fallback if it ever fails: `pkgs.onnxruntime` + `-Donnxruntime.native.path`.
- [`nix/models.nix`](../../nix/models.nix): `fetchurl` of both models with the hashes from ADR 0003 / ADR 0008, imported by `devenv.nix` and exported as `STT_WHISPER_MODEL` and `STT_SPEAKER_MODEL`. Reused by the flake (roadmap item 16).
- No template boilerplate: no `env.GREET`, `scripts.hello`, `enterShell` greeting, or commented-out template sections.
- `enterTest` (run by `devenv test`) holds the automated checks below; the expected model hashes are written out in the test, independent of `nix/models.nix`.
- Test reporting: devenv 2.x writes `enterTest` stdout to its activity log, so the per-check `ok:` lines are not shown. The pass signal is `devenv test` finishing with all steps ✓ and exit code 0; a failing check stops the script and its `FAILED:` line goes to devenv's error output.

## Test list

Each item is one check in `enterTest` (`devenv test`), except where marked manual.

- [x] The shell provides `node` and `npm`
- [x] The shell provides a JDK 21 `java`, `kotlin`, and `gradle`
- [x] The shell provides `whisper-cli`, `ffmpeg`, `sqlite3`, `curl`, and `jq`
- [x] `STT_WHISPER_MODEL` points to a file whose sha256 matches ADR 0008
- [x] `STT_SPEAKER_MODEL` points to a file whose sha256 matches ADR 0003
- [x] Entering the shell prints no template greeting (`hello from devenv`) — manual: `devenv shell -- true`

"The ONNX Runtime native library loads from the JVM inside the shell" moved to roadmap item 08 (needs the backend build).

Results, 2026-10-05:

- Agent sandbox (`enterTest` body run with bash, no Nix): red `FAILED: node and npm` (no node in the sandbox); with stub `node`/`npm` and the real models all five checks `ok`; a wrong file as `STT_WHISPER_MODEL` fails the whisper hash check.
- User, in the worktree: `devenv test` green on the first test commit and on the final branch state (all steps ✓, exit 0); `devenv shell -- true` printed nothing.
- Agent, during review: the user's built test script run in the user's built shell environment printed all five `ok:` lines, exit 0.

## Acceptance criteria

- `devenv test` completes green: all steps ✓, no failure output, exit 0.
- `devenv shell -- true` prints no `hello from devenv`.
- `devenv.nix` contains no template boilerplate or commented-out template sections.
- `nix/models.nix` exists and is imported by `devenv.nix`; hashes match ADR 0003 and ADR 0008.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-05: user instructed agents to add every needed development tool to `devenv.nix` directly; node/npm, curl, jq, and `nix/models.nix` added under that instruction.
- 2026-10-05: user accepted devenv's default Node and keeping the global `tsc`; recorded as ADR 0010.
- 2026-10-05: user asked to finish item 01; proposal accepted as written, test list finalized by the agent (seed + `curl`/`jq`, ONNX check moved to item 08).
- 2026-10-05: user ran `devenv test` and `devenv shell -- true`, both green; decided that the `devenv test` green summary is the reporting signal and that the commented-out template sections are removed as a `Refactor:` commit.
- 2026-10-05: user approved two `Refactor:` commits (template comments, unused `lib`/`config`/`inputs` args) and declined an ADR for the test-reporting decision.
- 2026-10-05: user approved the diff; fast-forward merged into `main`.
