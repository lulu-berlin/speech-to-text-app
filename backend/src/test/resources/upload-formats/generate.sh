#!/usr/bin/env bash
# The upload-format fixtures (roadmap item 61): one 0.5 s file per extension the app accepts
# (backend/src/main/resources/allowed-extensions.json). UploadFormatsSpec decodes each through the
# real chunker; the canonical FLAC's duration varies a little with lossy codecs' priming (0.46-0.54 s).
#
# Generated 2026-10-18 with ffmpeg 8.1.2 from the flake's nixpkgs pin (494ce7fd), run from this
# directory. sample.amr needs an AMR-NB encoder, which the small and headless variants do not
# build; the test stack carries only the decoder. Generate it from the same pin with the full
# variant (ffmpeg_8-full's libopencore_amrnb):
#
#   nix shell 'github:NixOS/nixpkgs/494ce7fd23ff6a5dff39e1fb11e9b6f2ac74bf25#ffmpeg_8-full' -c \
#     ffmpeg -y -loglevel error \
#       -f lavfi -i 'sine=frequency=440:sample_rate=8000:duration=0.5' \
#       -ac 1 -c:a libopencore_amrnb -b:a 12.2k sample.amr
set -euo pipefail
cd "$(dirname "$0")"

tone=(-f lavfi -i "sine=frequency=440:sample_rate=44100:duration=0.5")
video=(-f lavfi -i "testsrc=size=64x64:rate=10:duration=0.5" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=0.5")

ffmpeg -y -loglevel error "${tone[@]}" -c:a pcm_s16le sample.wav
ffmpeg -y -loglevel error "${tone[@]}" -c:a flac sample.flac
ffmpeg -y -loglevel error "${tone[@]}" -c:a libmp3lame -b:a 64k sample.mp3
ffmpeg -y -loglevel error "${tone[@]}" -c:a libvorbis -q:a 3 sample.ogg
ffmpeg -y -loglevel error "${tone[@]}" -c:a libvorbis -q:a 3 sample.oga
ffmpeg -y -loglevel error "${tone[@]}" -c:a libopus -b:a 32k sample.opus
ffmpeg -y -loglevel error "${tone[@]}" -c:a aac -b:a 64k sample.m4a
ffmpeg -y -loglevel error "${tone[@]}" -c:a aac -b:a 64k -f adts sample.aac
ffmpeg -y -loglevel error "${tone[@]}" -c:a wmav2 -b:a 64k sample.wma
ffmpeg -y -loglevel error "${tone[@]}" -c:a pcm_s16be sample.aif
ffmpeg -y -loglevel error "${tone[@]}" -c:a pcm_s16be sample.aiff
ffmpeg -y -loglevel error "${tone[@]}" -c:a flac sample.mka

ffmpeg -y -loglevel error "${video[@]}" -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -shortest sample.mp4
ffmpeg -y -loglevel error "${video[@]}" -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -shortest sample.m4v
ffmpeg -y -loglevel error "${video[@]}" -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -shortest sample.mov
ffmpeg -y -loglevel error "${video[@]}" -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -shortest sample.3gp
ffmpeg -y -loglevel error "${video[@]}" -c:v libvpx-vp9 -b:v 50k -c:a libopus -b:a 32k -shortest sample.webm
ffmpeg -y -loglevel error "${video[@]}" -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -shortest sample.mkv
