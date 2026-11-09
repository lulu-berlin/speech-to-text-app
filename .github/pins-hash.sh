#!/usr/bin/env bash
# The sha256 that keys the CI caches (roadmap item 97): one script for the JVM downloads cache and
# the Nix store cache, called through .github/actions/pins-hash, so the pin set is hashed the same
# way everywhere.
#
#   .github/pins-hash.sh          versions.json's pin set
#   .github/pins-hash.sh --nix    the pin set plus the lock files, devenv.nix and nix/
#
# Prints the 64-hex digest and nothing else; the caller adds its platform and namespace prefix.
set -euo pipefail

# Only what the bootstrap downloads — the tools, models and fixtures and their pins, not the
# nixpkgs-derived versions — so a nixpkgs bump does not throw away the ~1 GB of models. The
# node/pnpm/gradle installs live in version-suffixed directories and re-download on their own.
pins=$(jq -Sec '{downloads, models, fixtures, whisperCpp, ffmpeg}' versions.json)

if [ "${1:-}" = "--nix" ]; then
  # shellcheck disable=SC2046 # the nix/ file names carry no spaces; the split is the file list
  { cat flake.lock devenv.lock devenv.yaml devenv.nix $(find nix -type f | sort); printf '%s' "$pins"; } |
    { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } |
    cut -d' ' -f1
else
  printf '%s' "$pins" | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | cut -d' ' -f1
fi
