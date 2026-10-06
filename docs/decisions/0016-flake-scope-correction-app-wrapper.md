# 0016 — Flake scope correction: app wrapper in item 02

Status: accepted · Date: 2026-10-05

Supersedes: the "flake scope" decision in [0014](0014-build-tooling-gradle-catalog-no-wrapper.md) (consultation decision 7 and its consequences), which claimed the app wrapper and `nix run .` remain item 16. The other ADR 0014 decisions (version catalog, no Gradle wrapper) are unaffected.

## Context
The reviewer of roadmap item 02 found ADR 0014 out of date: the user requested (2026-10-05) and received `bin/speech-to-text-app` in item 02, while ADR 0014 still said the app wrapper and `nix run .` remain item 16. The correction must also record how the wrapper handles command-line arguments, since that behavior is visible to operators today.

## Decision
- **`bin/speech-to-text-app` ships in roadmap item 02** as the flake `default` package: a `stdenv` derivation (not `symlinkJoin`) merging the frontend and the backend runtime jars and wrapping `java` with `makeWrapper`.
- **Static layout:** the frontend is installed to `$out/share/stt-app/static`, which is the package's `STT_STATIC_DIR`. The backend jars (`$out/lib`) and the wrapper script (`$out/bin`) live outside the HTTP-served static root, so the app never serves its own files over HTTP.
- **Command-line arguments:** the wrapper passes the main class as a fixed argument (`--add-flags "stt.MainKt"`) and forwards any user-supplied arguments after it to `java`. `stt.Main` currently accepts but ignores arguments — no argument handling exists yet; the behavior of real CLI arguments is decided in item 16.
- **Remaining item 16 scope:** full `STT_*` environment wiring (data dirs, whisper/ffmpeg/model binaries), model assets, and `nix run .`.

## Consequences
- Item 02's acceptance no longer contradicts ADR 0014; item 16 narrows to full env/model wiring and `nix run .`.
- Operators get a runnable artifact from `nix build .` since item 02; its configuration surface is fixed (`STT_STATIC_DIR` + `CLASSPATH` set by the wrapper, `STT_PORT` from the environment).
- Any later change to where the static files live or how the wrapper is invoked must keep the wrapper and jars outside the static root (security invariant recorded in the flake comment).
