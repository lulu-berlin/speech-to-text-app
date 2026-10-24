#!/usr/bin/env bash
# The Windows audio-only surface proof (roadmap item 68, test-list item 1): the staged bin holds
# exactly one ffmpeg.exe — no ffprobe, no ffplay — and the exe's configure line equals
# nix/ffmpeg-enable.json exactly (nix/ffmpeg-audio-only.sh), so no GPL/version3 code, no video,
# network or capture component can slip in; and every DLL it imports is a Windows system DLL, so a
# stock Windows install needs nothing beside it.
#
# $1 = the staged bin directory holding ffmpeg.exe; $2 = the expected surface
# (nix/ffmpeg-enable-list.sh expected nix/ffmpeg-enable.json).
set -euo pipefail

bindir=$1
expected=$2
exe=$bindir/ffmpeg.exe

fail() {
  echo "windows-ffmpeg-surface: $1" >&2
  exit 1
}

[ -x "$exe" ] || fail "$exe is missing"
entries=$(ls -A "$bindir")
[ "$entries" = ffmpeg.exe ] || fail "the staged bin holds more than ffmpeg.exe: $(echo $entries)"

# The configure line: the same exact-set check the Linux and macOS builds run.
bash "$(dirname "$0")/ffmpeg-audio-only.sh" "$exe" "$expected"

# The imports: every DLL is one Windows itself provides (api-ms-win-* are API-set virtual names).
imports=$(objdump -p "$exe" | sed -n 's/^[[:space:]]*DLL Name: //p')
[ -n "$imports" ] || fail "objdump -p lists no DLL imports (not a Windows executable?)"
offenders=$(grep -E '^(msvcp|vcruntime|vcomp|lib|av|sw)' <<<"$imports" || true)
[ -z "$offenders" ] || fail "the exe imports a non-static runtime or library: $(echo $offenders)"
system32=${SYSTEM32:-$(cygpath -u "${SYSTEMROOT:-C:/Windows}")/System32}
while IFS= read -r dll; do
  case $dll in
    api-ms-win-*.dll) continue ;;
  esac
  [ -f "$system32/$dll" ] || fail "the exe imports $dll, which is not in the Windows system directory"
done <<<"$imports"

echo "windows-ffmpeg-surface: ok (imports: $(echo $imports | tr '\n' ' '))"
