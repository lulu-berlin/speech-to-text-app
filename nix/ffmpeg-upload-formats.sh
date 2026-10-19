#!/usr/bin/env bash
# The upload-format proof for roadmap item 62 (test-list item 3; the review's two additions): decode
# every committed fixture named by `allowed-extensions.json`, plus the accepted-upload variants the
# 19 fixtures do not cover, through the shipped artifact with the app's canonical FLAC args, and
# validate each result from its own STREAMINFO — the artifact ships no ffprobe. The check also
# rejects a fixture whose extension the app no longer accepts.
#
# $1 = the shipped ffmpeg; $2 = the fixture directory; $3.. = the accepted extensions.
set -euo pipefail
ffmpeg=$1
fixtures=$2
shift 2
extensions=("$@")

fail() {
  echo "ffmpeg-upload-formats: $1" >&2
  exit 1
}

work=${TMPDIR:-/tmp}/clips
mkdir -p "$work"

# check_canonical INPUT LABEL: decode with the app's canonical args, then validate the FLAC.
check_canonical() {
  input=$1
  label=$2
  clip=$work/$label.flac
  "$ffmpeg" -y -loglevel error -i "$input" -vn -ar 16000 -ac 1 -sample_fmt s16 -c:a flac -frame_size 4000 "$clip" ||
    fail "the shipped ffmpeg failed to decode $label"
  [ "$(od -An -tx1 -N4 "$clip" | tr -d ' \n')" = 664c6143 ] ||
    fail "$label did not encode to FLAC"
  set -- $(od -An -tu1 -j4 -N4 "$clip")
  [ $(( $1 & 0x7f )) -eq 0 ] || fail "$label: the first metadata block is not STREAMINFO"
  [ $(( ($2 << 16) | ($3 << 8) | $4 )) -eq 34 ] || fail "$label: STREAMINFO is not 34 bytes"
  # STREAMINFO data starts at file byte 18: 20-bit rate, 3-bit channels-1, 5-bit bits-1, 36-bit
  # total across bytes 18-25 (the app's streamInfoTotalSamples reads bytes 21-25).
  set -- $(od -An -tu1 -j18 -N8 "$clip")
  rate=$(( ($1 << 12) | ($2 << 4) | ($3 >> 4) ))
  channels=$(( (($3 >> 1) & 7) + 1 ))
  bits=$(( ((($3 & 1) << 4) | ($4 >> 4)) + 1 ))
  total=$(( (($4 & 0x0f) << 32) | ($5 << 24) | ($6 << 16) | ($7 << 8) | $8 ))
  [ "$rate" = 16000 ] || fail "$label: rate $rate, expected 16000"
  [ "$channels" = 1 ] || fail "$label: $channels channels, expected 1"
  [ "$bits" = 16 ] || fail "$label: $bits bits, expected 16"
  # 0.5 s, plus the priming/trimming spread of the lossy decoders (0.46-0.54 s measured).
  [ "$total" -ge 6400 ] && [ "$total" -le 9600 ] ||
    fail "$label: $total samples, expected the 0.4-0.6 s spread"
}

for extension in "${extensions[@]}"; do
  input=$fixtures/sample.$extension
  [ -f "$input" ] || fail "no fixture for the accepted extension $extension"
  check_canonical "$input" "sample.$extension"
done
# Accepted uploads the 19 extension fixtures do not cover: bit depths/endiannesses the formats can
# carry, and the LOAS/LATM flavour of `.aac`.
for variant in variant-24bit.wav variant-pcm32be.mka variant-f32be.aiff variant-latm.aac; do
  input=$fixtures/$variant
  [ -f "$input" ] || fail "the variant fixture $variant is missing"
  check_canonical "$input" "$variant"
done
# A fixture for an extension the app no longer accepts is the same drift in the other direction.
for input in "$fixtures"/sample.*; do
  extension=${input##*.}
  case " ${extensions[*]} " in
    *" $extension "*) ;;
    *) fail "a fixture exists for the unaccepted extension $extension" ;;
  esac
done
