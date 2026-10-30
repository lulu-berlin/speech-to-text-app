# Item 74, test-list item "A ja transcript renders Japanese glyph forms; zh-Hans and zh-Hant stay
# distinct": the bundle's own fontconfig configuration resolves Han text to the face of the text's
# language. The transcript's `lang` attribute (item 74's language cycles) is what supplies it, so a
# Japanese transcript is drawn with IBM Plex Sans JP's forms, not the Simplified Chinese face's, and
# the two Chinese forms keep their own faces.
#
# fontconfig 2.17 (the flake's pin) has no `zh-hans`/`zh-hant` orthographies - queried over a CJK
# font set, `:lang=zh-hans` and `:lang=zh-hant` match nothing while `:lang=zh-cn` and `:lang=zh-tw`
# do - so the two Chinese forms are asked for with the tags it knows.
{ pkgs, fontsConf }:
pkgs.runCommand "font-forms" {
  nativeBuildInputs = [pkgs.fontconfig];
} ''
  export HOME=$TMPDIR
  export FONTCONFIG_FILE=${fontsConf}

  fail() {
    echo "font-forms: $1" >&2
    exit 1
  }

  # 漢 (U+6F22) is common to all three Han faces: the language alone decides which one fontconfig
  # returns. The pattern starts with ':' - fontconfig reads anything before the first ':' as a family
  # name - and names no family, like a page whose text has a `lang` and the generic UI font.
  expect() {
    language=$1
    expected=$2
    actual=$(fc-match --format='%{family[0]}\n' ":lang=$language:charset=6f22")
    [ "$actual" = "$expected" ] ||
      fail "a $language text resolves to '$actual', not '$expected'"
  }
  expect ja "IBM Plex Sans JP"
  expect zh-cn "IBM Plex Sans SC"
  expect zh-tw "IBM Plex Sans TC"
  # Item 74 (discovered): whisper marks Chinese transcripts with its own codes, not fontconfig's
  # territory tags - `zh` (written Simplified, the bundle's first Han fallback) and `yue` (Cantonese,
  # written Traditional).
  expect zh "IBM Plex Sans SC"
  expect yue "IBM Plex Sans TC"
  echo ok > $out
''
