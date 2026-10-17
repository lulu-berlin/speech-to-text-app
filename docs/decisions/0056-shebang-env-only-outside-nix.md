# 0056 — Which shell a script starts with: `env` only where Nix is absent, pinned store interpreters otherwise

Status: accepted · Date: 2026-10-15

## Context

Every script needs a shebang, and the repository now has two very different worlds. Code that runs
*inside* a Nix store is executed by an interpreter Nix has pinned: `pkgs.writeShellScript` and
`pkgs.writeShellApplication` bake the store's own `bash` into the file, so the shell version is a
function of the build inputs — reproducible, and unaffected by whatever `bash` happens to be on the
machine's `PATH`.

The upcoming single-file desktop bundle (ADR 0053) is the opposite case: it exists for machines with
no Nix at all, and the first thing the kernel reads from it is the shebang. A store-path shebang
there is fatal — the file would not even start (`/nix/store/…-bash/bin/bash` does not exist on a
Fedora or Arch machine). This was caught in the bundle's launcher, which used
`writeShellScript` and therefore could not have run on its own target.

## Decision

- **A script that must run where Nix is absent uses `#!/usr/bin/env <interpreter>`** — currently only
  the desktop bundle's launcher, `#!/usr/bin/env sh` (`nix/desktop-bundle.nix`). Such a script is
  written in POSIX `sh` (no bashisms), because the host's own `sh` is what `env` will find.
- **Everything else that Nix builds keeps the pinned store interpreter** — `pkgs.writeShellScript` /
  `writeShellApplication`, i.e. `#!/nix/store/…-bash/bin/bash`. Pinning wins wherever the script
  cannot leave the store (the package's wrapped `bin/stt-app`, `apps.update-versions`, the
  `docker-root` entrypoint): the interpreter is then part of the closure instead of a guess about
  the host.
- **The repository's own developer scripts** (`.envrc`, `dev.sh`, `backend/dev-run.sh`) use
  `#!/usr/bin/env bash`: they are plain checkout files that a human runs from the development shell,
  where `bash` comes from the pinned shell on `PATH`. They never ship.
- **Tool-generated files keep what their tool wrote**: `gradlew` (Gradle's wrapper) says `#!/bin/sh`,
  and any future regeneration would restore that. Docs checks and other Node scripts need no shebang
  at all — they are run as `node <file>` (ADR 0054).

## Consequences

- The rule is decided by *where the file has to run*, not by taste: store-pinned when only Nix
  executes it, `env` when a stranger's machine does.
- A bundle launcher's POSIX constraint is real work: no `local`, arrays, `[[ ]]` or bash-only flags.
  Accepted, because the alternative is not running at all; the launcher is small and its logic is
  simulated in the sandbox (self-extraction, offset arithmetic, exec — run under `dash`, which also
  showed that its final `exec` must remain the last statement: the shell would otherwise read the
  appended payload as script).
- `gradlew`'s `#!/bin/sh` and Nix-generated wrappers are documented exceptions rather than
  violations, so a future contributor does not "fix" a generated file.
- If a second artefact ever has to run outside Nix, this ADR already governs it: `env` shebangs,
  POSIX syntax, and a note in that file saying why.
