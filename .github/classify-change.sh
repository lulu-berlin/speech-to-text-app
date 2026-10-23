#!/usr/bin/env bash
# Classifies a pull request's changed paths — newline-separated on stdin, as `git diff
# --name-only` prints them — and prints `code=true` when they touch code and `code=false` when
# they are documentation only (roadmap item 80). The `changes` job pipes the diff in and appends
# the printed line to $GITHUB_OUTPUT.
#
# Documentation is `docs/`, `roadmap/`, any `*.md` file and `LICENSE`; everything else is code,
# including `.github/`, `.editorconfig`, `.gitattributes`, `devenv.nix`, `flake.nix`,
# `versions.json`, `build.gradle.kts` and `stt.cmd` (decided 2026-10-23). An empty diff is not
# "documentation only" — it is a comparison that told us nothing — so it runs everything.
set -euo pipefail

code=false
paths=0
while IFS= read -r path; do
  [ -n "$path" ] || continue
  paths=$((paths + 1))
  case $path in
    docs/*|roadmap/*|LICENSE|*.md) ;;
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
