#!/usr/bin/env bash
# The Windows audio-path proof (roadmap item 68, test-list item 2): the committed per-extension
# fixtures decode to the canonical FLAC, and the app's own ffmpeg command lines — f32le decode,
# silencedetect, the atrim window and the V10 concat backfill — work, run with the Windows binary
# the asset job built. The scripts and arguments are item 62's, unchanged: the flake checks run the
# same two scripts on the Linux and macOS binaries.
#
# $1 = the ffmpeg to prove; $2 = the repository root (holds the fixtures and the extension list).
set -euo pipefail

ffmpeg=$1
root=$2
fixtures=$root/backend/src/test/resources/upload-formats

# The accepted extensions are the app's list, not a copy: an accepted extension without a fixture
# (or a fixture for an unaccepted one) fails nix/ffmpeg-upload-formats.sh.
extensions=$(jq -r '.[]' "$root/backend/src/main/resources/allowed-extensions.json")
bash "$root/nix/ffmpeg-upload-formats.sh" "$ffmpeg" "$fixtures" $extensions
bash "$root/nix/ffmpeg-app-uses.sh" "$ffmpeg" "$fixtures"

echo "windows-ffmpeg-fixtures: ok"
