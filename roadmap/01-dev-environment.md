# 01 — Dev environment

Status: draft
Depends on: —

## Goal

Make `devenv.nix` provide every tool and model the app needs during development (Node/npm for the frontend, JDK/Gradle for the backend, whisper-cli, ffmpeg, SQLite, both models), and remove the devenv template boilerplate.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): dependencies come from devenv; Nix flake is the deliverable.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): no Python toolchain is added.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): speaker model URL and sha256; ONNX Runtime Java.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): whisper model URL and sha256.

## Proposal (pending approval)

Current `devenv.nix` (observed): packages `git`, `whisper-cpp` overridden with `vulkanSupport = true`, `ffmpeg`, `vulkan-tools`, `sqlite`; `languages.kotlin.enable = true`; `languages.typescript.enable = true`; plus template boilerplate. Node/npm are not on PATH today.

- Add `languages.javascript.enable = true;` and `languages.javascript.npm.enable = true;` (node + npm).
- Keep `languages.kotlin` (JDK 21, Gradle) and `languages.typescript`.
- Keep the `whisper-cpp` Vulkan override, `ffmpeg`, `vulkan-tools`, `sqlite` (and `git`).
- ONNX Runtime native library:
  - Default: rely on the native library bundled in the Maven artifact `com.microsoft.onnxruntime:onnxruntime`, and expose `LD_LIBRARY_PATH` containing `pkgs.stdenv.cc.cc.lib` (libstdc++) so the bundled lib loads on NixOS.
  - Only if the bundled lib fails to load on NixOS: add `pkgs.onnxruntime` and point the JVM at it with `-Donnxruntime.native.path=<path>`.
- Create `nix/models.nix`: `fetchurl` of both models with the hashes from ADR 0003 and ADR 0008:
  - whisper: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin`, sha256 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`.
  - speaker: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, sha256 `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`.
  - Imported by `devenv.nix`, which exports `STT_WHISPER_MODEL` and `STT_SPEAKER_MODEL` env vars pointing at the store paths. The same file is reused by the flake in item 16.
- Remove template boilerplate currently in `devenv.nix`:
  - `env.GREET = "devenv";` (line 11)
  - `scripts.hello.exec` echoing `hello from $GREET` (lines 41–43)
  - `enterShell` running `hello` and `git --version` (lines 46–49)
  - `enterTest` with the `git --version | grep ... "${pkgs.git.version}"` check (lines 58–61)

Nix evaluation cannot run inside the agent sandbox; every check here is run by the user.

## Open questions for the user

- Node version: nixpkgs default or a pinned LTS (e.g. `pkgs.nodejs_22`)?
- Keep `tsc` 7 globally (via `languages.typescript`) or rely only on the project-local `typescript` from `frontend/package.json`?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Entering the shell provides `node` and `npm`
- [ ] Entering the shell still provides `gradle`, a JDK 21 `java`, `kotlin`, `whisper-cli`, `ffmpeg`, `sqlite3`
- [ ] `STT_WHISPER_MODEL` points to an existing file whose sha256 matches ADR 0008
- [ ] `STT_SPEAKER_MODEL` points to an existing file whose sha256 matches ADR 0003
- [ ] Entering the shell prints no template greeting (`hello from devenv`)
- [ ] The ONNX Runtime native library loads from the JVM inside the shell (checked once the backend exists in item 02/08)

## Acceptance criteria

- User runs `devenv shell -- node --version && npm --version && gradle --version && whisper-cli --help && test -f "$STT_WHISPER_MODEL" && test -f "$STT_SPEAKER_MODEL"` successfully.
- `devenv.nix` contains no `env.GREET`, `scripts.hello`, `enterShell` greeting, or `enterTest` git check.
- `nix/models.nix` exists and is imported by `devenv.nix`; hashes match ADR 0003 and ADR 0008.
- All test-list items ticked and green; user approved the diff.

## Approval log

_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
