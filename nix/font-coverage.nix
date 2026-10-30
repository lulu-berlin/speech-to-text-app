# The bundle's font coverage (roadmap item 74): every language in docs/api/languages.json must have
# every character of its sample (nix/fixtures/language-samples.json) in the bundle's shipped font
# set. Coverage is read through the fonts' cmaps as the bundle's own fontconfig configuration sees
# them, so a language the bundle has no font for fails here instead of rendering as missing glyphs
# on the user's machine.
{
  pkgs,
  fontsConf,
  samples ? ./fixtures/language-samples.json,
  languages ? ../docs/api/languages.json,
}:
pkgs.runCommand "font-coverage" {
  nativeBuildInputs = [pkgs.bash pkgs.coreutils pkgs.fontconfig pkgs.jq];
} ''
  export HOME=$TMPDIR

  # The bundle's own font configuration: only its fonts, nothing of the host's.
  export FONTCONFIG_FILE=${fontsConf}

  cp ${samples} samples.json
  cp ${languages} languages.json

  # Every language must have a sample: a language added to the contract without one would otherwise
  # pass unnoticed. `auto` is the request placeholder, not a language.
  jq -r '.languages[].code' languages.json | grep -vx auto | sort > expected-codes.txt
  jq -r 'keys[]' samples.json | sort > sample-codes.txt
  if ! diff -u expected-codes.txt sample-codes.txt; then
    echo "font-coverage: nix/fixtures/language-samples.json must carry one sample for every language in docs/api/languages.json (all but auto)" >&2
    exit 1
  fi

  # Every character of every sample, once per code point, is looked up in the shipped fonts: fc-list
  # with a one-code-point charset pattern names the fonts that contain it.
  jq -r 'to_entries[] | .key as $code | .value | explode[] | [$code, .] | @tsv' samples.json > pairs.tsv
  cut -f2 pairs.tsv | sort -u > codepoints.txt
  : > missing-hex.txt
  while read -r cp; do
    hex=$(printf '%04x' "$cp")
    if [ -z "$(fc-list --format='%{family}\n' ":charset=$hex")" ]; then
      echo "$hex" >> missing-hex.txt
    fi
  done < codepoints.txt

  if [ -s missing-hex.txt ]; then
    {
      echo "font-coverage: the bundle's fonts lack glyphs for languages in docs/api/languages.json:"
      while IFS=$'\t' read -r code cp; do
        hex=$(printf '%04x' "$cp")
        if grep -qxF "$hex" missing-hex.txt; then printf '  %s U+%s\n' "$code" "$hex"; fi
      done < pairs.tsv
    } | sort -u >&2
    exit 1
  fi
  echo ok > $out
''
