#!/usr/bin/env bash
# The audio-only configuration proof for roadmap item 62 (test-list item 2): compare the built
# ffmpeg's `configuration:` line against the component lists `nix/ffmpeg.nix` builds from, exactly.
#
# Containment alone cannot see an extra component (a video decoder added to --enable-decoder=… passes
# it), so every `--enable-<kind>=VALUE` is parsed, the kind set is checked against the expected kinds,
# and each value is compared as a set: an extra or a missing component fails, naming it. The expected
# file holds `flag VALUE` (a plain flag that must be present), `forbid VALUE` (a token that must be
# absent) and `<kind> comma,separated,components` lines.
#
# $1 = the ffmpeg to inspect; $2 = the expected file.
set -euo pipefail
export LC_ALL=C
ffmpeg=$1
expected=$2

fail() {
  echo "ffmpeg-audio-only: $1" >&2
  exit 1
}

work=${TMPDIR:-/tmp}
mkdir -p "$work"

# FFmpeg sh-quotes a configure value that has no shell-safe characters (the component lists' commas),
# so the configuration line reads --enable-demuxer='aac,aiff,...'; drop the quotes.
configuration=$("$ffmpeg" -hide_banner -version | sed -n 's/^configuration: //p' | tr -d "'")
[ -n "$configuration" ] || fail "ffmpeg -version prints no configuration line"

declare -A want=()
want_kinds=()
while IFS=' ' read -r kind value; do
  [ -n "$kind" ] || continue
  case $kind in
    flag)
      grep -qF -- "$value" <<<"$configuration" || fail "the configuration line lacks $value"
      ;;
    forbid)
      if grep -qF -- "$value" <<<"$configuration"; then
        fail "the configuration line has $value"
      fi
      ;;
    *)
      want[$kind]=$value
      want_kinds+=("$kind")
      ;;
  esac
done <"$expected"

declare -A got=()
for token in $configuration; do
  case $token in
    --enable-*=*)
      kind=${token#--enable-}
      kind=${kind%%=*}
      [ -n "${want[$kind]+x}" ] ||
        fail "the configuration line enables $kind components, which the audio-only list does not"
      [ -z "${got[$kind]+x}" ] || fail "the configuration line has more than one --enable-$kind"
      got[$kind]=${token#*=}
      ;;
  esac
done

for kind in "${want_kinds[@]}"; do
  [ -n "${got[$kind]+x}" ] || fail "the configuration line has no --enable-$kind"
  tr ',' '\n' <<<"${got[$kind]}" | sort >"$work/actual"
  tr ',' '\n' <<<"${want[$kind]}" | sort >"$work/wanted"
  extra=$(comm -23 "$work/actual" "$work/wanted")
  missing=$(comm -13 "$work/actual" "$work/wanted")
  [ -z "$extra" ] || fail "enables $kind $(echo $extra), which the audio-only list does not"
  [ -z "$missing" ] || fail "lacks $kind $(echo $missing)"
done
