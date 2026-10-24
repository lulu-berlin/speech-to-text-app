#!/usr/bin/env bash
# Renders the shared audio-only surface (roadmap items 62 and 68) from nix/ffmpeg-enable.json,
# the one data file nix/ffmpeg.nix builds from, flake.nix renders the Linux/macOS expectation
# from, and the manual windows-ffmpeg-asset job configures and proves the Windows build against.
#
#   expected <json>   the `flag`/`forbid`/`<kind> comma,list` file nix/ffmpeg-audio-only.sh
#                     compares a build's configure line against, exactly
#   configure <json>  the `--enable-<kind>=...` arguments a native build configures with
set -euo pipefail

mode=${1:?usage: ffmpeg-enable-list.sh expected|configure <nix/ffmpeg-enable.json>}
json=${2:?usage: ffmpeg-enable-list.sh expected|configure <nix/ffmpeg-enable.json>}

case $mode in
  expected)
    jq -r '.flags[] | "flag \(.)"' "$json"
    jq -r '.forbid[] | "forbid \(.)"' "$json"
    jq -r '.enable[] | "\(.kind) \(.components | join(","))"' "$json"
    ;;
  configure)
    jq -r '.flags[]' "$json"
    jq -r '.enable[] | "--enable-\(.kind)=\(.components | join(","))"' "$json"
    ;;
  *)
    echo "ffmpeg-enable-list: unknown mode $mode (expected or configure)" >&2
    exit 2
    ;;
esac
