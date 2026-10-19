#!/usr/bin/env bash
# The app's other ffmpeg uses against the shipped artifact (roadmap item 62, test-list item 4):
# AudioDecoder's f32le decode, the silencedetect/null scan, WindowCutter's atrim cut and the V10
# concat backfill. The commands are the app's own argument lists; the atrim/concat results are read
# from their FLAC STREAMINFO, so no ffprobe is needed.
#
# $1 = the shipped ffmpeg; $2 = the upload-format fixture directory.
set -euo pipefail
ffmpeg=$1
fixtures=$2
work=${TMPDIR:-/tmp}/app-uses
mkdir -p "$work"

fail() {
  echo "ffmpeg-app-uses: $1" >&2
  exit 1
}

streaminfo_total() {
  set -- $(od -An -tu1 -j18 -N8 "$1")
  echo $(( (($4 & 0x0f) << 32) | ($5 << 24) | ($6 << 16) | ($7 << 8) | $8 ))
}

# AudioDecoder: 16 kHz mono f32le, from a video container; the video track must be ignored.
"$ffmpeg" -y -loglevel error -i "$fixtures/sample.webm" -vn -f f32le -ac 1 -ar 16000 "$work/audio.f32" ||
  fail "f32le decode of sample.webm failed"
[ -s "$work/audio.f32" ] || fail "f32le decode produced no PCM"
[ $(( $(wc -c < "$work/audio.f32") % 4 )) -eq 0 ] || fail "f32le output is not 32-bit samples"

# AudioChunker.detectSilences: the null muxer with `-` (the pipe protocol) and no video mapping.
"$ffmpeg" -hide_banner -nostats -i "$fixtures/sample.webm" -vn -af silencedetect=noise=-30dB:d=0.5 -f null - \
  >/dev/null || fail "silencedetect on sample.webm failed"

# WindowCutter: the canonical clip, then a 0.2 s sample window.
"$ffmpeg" -y -loglevel error -i "$fixtures/sample.wav" -vn -ar 16000 -ac 1 -sample_fmt s16 -c:a flac -frame_size 4000 "$work/canonical.flac" ||
  fail "canonical encode failed"
"$ffmpeg" -y -loglevel error -i "$work/canonical.flac" -vn -af atrim=start_sample=1600:end_sample=4800 -ac 1 -sample_fmt s16 -c:a flac "$work/window.flac" ||
  fail "atrim window cut failed"
[ "$(streaminfo_total "$work/window.flac")" = 3200 ] || fail "the window cut did not yield 3200 samples"

# V10__BackfillCanonicalClip: two stored clips through a concat list, re-encoded canonically.
cp "$work/canonical.flac" "$work/one.flac"
cp "$work/canonical.flac" "$work/two.flac"
printf "file '%s'\nfile '%s'\n" "$work/one.flac" "$work/two.flac" > "$work/concat.txt"
"$ffmpeg" -y -loglevel error -f concat -safe 0 -i "$work/concat.txt" -vn -ar 16000 -ac 1 -sample_fmt s16 -c:a flac -frame_size 4000 "$work/backfill.flac" ||
  fail "the V10 concat backfill failed"
[ "$(streaminfo_total "$work/backfill.flac")" = 16000 ] || fail "the backfill did not yield 16000 samples"
