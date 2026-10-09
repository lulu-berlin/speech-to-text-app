# 19 — Generic Unix build recipe with minimal dependencies

Status: spec-approved
Depends on: [16](16-nix-flake-package.md)

## Goal

A documented recipe that builds and runs the app on a plain Unix machine — no Nix, no Docker, no bundle: a short list of prerequisites, a fixed set of commands, and a run command with environment variables. It is written for the user or for a machine that has none of the repo's tooling, and it is kept small on purpose ("minimal and likely to work", user, 2026-10-09).

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): the Nix flake is the canonical deliverable (item 16); this recipe is a documented alternative for machines without Nix, and both ship the same app.
- The repo's own pins are the recipe's pins: JDK 21 with Gradle 8.14 ([ADR 0014](../docs/decisions/0014-build-tooling-gradle-catalog-no-wrapper.md) — there is no Gradle wrapper), pnpm 11 ([ADR 0012](../docs/decisions/0012-frontend-dependency-management-pnpm.md)), whisper-cli 1.9.2, ffmpeg (9.0.1 tested), util-linux `setsid` ([ADR 0020](../docs/decisions/0020-process-runner-jna-signals-process-groups.md)), and the two models with the hashes from `nix/models.nix`.
- Docker was considered for a portable build and aborted (item 19, 2026-10-09); this recipe replaces that role by documentation instead of an image.

## Proposal (approved 2026-10-09)

The item writes two artifacts in the repo:
- **`docs/recipe-unix.md`** — the recipe, structured as:
  1. **Prerequisites** (the minimal set, with the exact versions the repo is built and tested against): `git`, a JDK 21 with Gradle 8.14, Node with pnpm 11, `curl` and `tar`, `ffmpeg`, whisper-cli 1.9.2, and `util-linux` (for `setsid`). One line each, with pointers for a distro without them (e.g. `apt install ffmpeg util-linux`; whisper-cli built from whisper.cpp `v1.9.2` with `cmake`, with whichever backend the machine wants — CPU, or a GPU backend (`-DGGML_VULKAN=ON`, CUDA, …). The recipe is not CPU-only: it uses the whisper-cli the user installed, so its GPU story is exactly the host's, as if the app ran directly).
  2. **The models** — the two `curl` commands from `nix/models.nix` with their `sha256sum -c` checks, into e.g. `~/.local/share/stt-app/models/`.
  3. **The build** — from a fresh checkout: `cd backend && gradle installDist` (Maven Central supplies the dependencies), `cd frontend && pnpm install --frozen-lockfile && pnpm build`.
  4. **The run** — a small POSIX `scripts/stt-app` example wrapper the item adds, or the documented equivalent:
     ```sh
     export STT_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/stt-app"
     export STT_SPEAKER_MODEL="$STT_DATA_DIR/models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
     export STT_WHISPER_MODEL="$STT_DATA_DIR/models/ggml-large-v3-turbo-q5_0.bin"
     export STT_WHISPER_CLI=$(command -v whisper-cli)
     export STT_FFMPEG=$(command -v ffmpeg)
     export STT_STATIC_DIR="$PWD/frontend/dist"
     export STT_DEVICE=auto           # auto = first GPU; cpu forces CPU; or an index
     # optional: export STT_PORT=8080  STT_THREADS=4
     exec java -cp 'backend/build/install/stt-backend/lib/*' stt.MainKt
     ```
  5. **Verify** — `curl localhost:8080/api/health` → `{"status":"ok"}`, the frontend loads at `/`, and a short clip (one of the committed LibriSpeech fixtures, or any audio) transcribes to COMPLETED.
  6. **What "minimal" buys and costs** — a short honesty box: the five prerequisites are ordinary packages on any Linux, the versions are the repo's tested ones (other versions may work, undocumented), and there is no hash-pinned universe like Nix provides; that is the point of the trade.
- **`scripts/stt-app`** — the wrapper from step 4, POSIX sh, `set -eu`, defaulting every `STT_*` variable and passing `"$@"` to `java`.

Scope: Linux. macOS needs `setsid` and a whisper build the recipe does not cover; it stays a future item (as in item 16).

Not in scope: bundling the JRE or the binaries into the recipe (the user decided: a recipe is instructions, not a bundle), a NixOS module, or a service unit.

## Open questions (answered 2026-10-09)

- What replaces Docker? → **Documentation**: a plain-tools recipe with a minimal prerequisite list, no Nix and no bundle.
- Self-contained output? → **No bundle.** The recipe is instructions: install these, run these.
- Where does it live? → **A new item 19** reusing the freed number, depending on item 16.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. Every item is verified by the user on a machine without Nix:

- [ ] From a fresh clone on a machine with only the listed prerequisites, `gradle installDist` and `pnpm build` succeed
- [ ] The model downloads verify against the hashes in `docs/recipe-unix.md` and `nix/models.nix`
- [ ] Starting the app via `scripts/stt-app` (or the documented commands) answers `GET /api/health` with `{"status":"ok"}`
- [ ] The frontend loads at `/`
- [ ] Uploading a short audio file transcribes it to COMPLETED with a transcript
- [ ] With a GPU-capable whisper-cli installed, `STT_DEVICE=auto` (or `gpu`) uses the GPU; the same app and models work under the Nix delivery (item 16) as well

## Acceptance criteria

- User-run on a machine without Nix: the recipe's commands, from a fresh clone, build and run the app, and a short clip transcribes.
- `docs/recipe-unix.md` is a faithful, minimal translation of the flake's assumptions (same engines, same models, same wrapper variables), and `scripts/stt-app` matches what the wrapper does.
- All test-list items ticked and verified; user approved the diff.

## Approval log

- 2026-10-09: item created to replace the aborted Docker item: a plain-tools Unix recipe (documentation plus a small example wrapper), minimal prerequisites, no Nix and no bundle (user's decision).
- 2026-10-09: corrected after test-list approval (user): the recipe must not read as CPU-only — it uses whatever whisper-cli the user installed, so a GPU-capable local whisper-cli works; the example default is `auto` (the first GPU), not `cpu`.
- 2026-10-09: test list (6 items) approved by the user; status `spec-approved`.
