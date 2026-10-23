#!/usr/bin/env bash
# Classifies a pull request's changed paths — newline-separated on stdin, as `git diff
# --name-only` prints them — and prints `code=true` when they touch code and `code=false` when
# they are documentation only (roadmap item 80). The `changes` job pipes the diff in and appends
# the printed line to $GITHUB_OUTPUT.
#
# Documentation is any `*.md` file and `LICENSE`; everything else is code, including `docs/api/`
# (its JSON is the API contract the backend tests read), `docs/backend-schema.sql`, images and every
# `.txt` (`desktop/jlink-modules.txt` builds the bundled JRE, `nix/fixtures/*.txt` backs a flake
# check), and also `.github/`, the dotfiles, `devenv.nix`, `flake.nix`, `versions.json` and
# `build.gradle.kts` (decided 2026-10-23, revised the same day). An empty diff is not
# "documentation only" — it is a comparison that told us nothing — so it runs everything.
set -euo pipefail

code=false
paths=0
while IFS= read -r path; do
  [ -n "$path" ] || continue
  paths=$((paths + 1))
  case $path in
    LICENSE|*.md) ;;
    *)
      code=true
      break
      ;;
  esac
done

if [ "$paths" -eq 0 ]; then
  code=true
fi

printf 'code=%s\n' "$code"
