# The desktop bundle's fonts (roadmap items 50 and 74, ADR 0053): IBM Plex as the app's face and the
# per-script fallbacks its languages need, plus the fontconfig configuration that exposes only them.
# It lives apart from nix/desktop-bundle.nix so the coverage check (nix/font-coverage.nix) judges
# exactly the set the bundle ships, not a hand-kept copy of it.
{ pkgs }:
let
  lib = pkgs.lib;

  # The fonts: IBM Plex Sans (the app's own typeface, ADR 0033) and the Plex families that cover the
  # scripts of the languages in docs/api/languages.json — Arabic (ar, fa, ur, ps, sd), Devanagari
  # (hi, mr, ne, sa), Hebrew (he, yi), Thai, Chinese SC and TC (zh, yue; SC lacks common Traditional
  # characters such as 見 and 門), Japanese and Korean. Only the three weights the UI uses
  # (regular, medium, semibold) and only TTF: 27 files, +32.9 MB to the bundle (zstd -19) and
  # +69.4 MB unpacked, against +179 MB / +329 MB for the whole families (measured 2026-10-15).
  #
  # Item 74: Plex has no font for the other scripts in that list (Tamil, Malayalam, Telugu,
  # Bengali/Assamese, Kannada, Armenian, Gurmukhi, Sinhala, Khmer, Georgian, Gujarati, Ethiopic,
  # Lao, Myanmar, Tibetan) nor for Hausa's ƙ ƴ Ɗ Ƙ Ƴ and Lingala's Ɛ Ɔ — the last two, and Yoruba's
  # ẹ ọ ṣ, are Latin Extended-B/Additional letters Plex Sans lacks too. Each gets the Noto family
  # for its script from the pin's noto-fonts; that package carries one weight per script family (and
  # Tibetan only in the Serif cut), so each ships the cut it has. 17 files, +10.0 MB unpacked and
  # +4.4 MB to the bundle (zstd -19, measured 2026-10-29). nix/font-coverage.nix proves every sample
  # of every language in docs/api/languages.json is covered by this set.
  plex = pkgs.ibm-plex.override {
    families = ["sans" "sans-arabic" "sans-devanagari" "sans-hebrew" "sans-thai" "sans-sc" "sans-tc" "sans-jp" "sans-kr"];
  };
  notoScripts = [
    # Latin Extended-B/Additional: Hausa ƙ ƴ Ɗ Ƙ Ƴ, Lingala Ɛ Ɔ, Yoruba ẹ ọ ṣ.
    "NotoSans.ttf"
    # Sindhi's extended Arabic letters sit beyond Plex Arabic's coverage.
    "NotoSansArabic.ttf"
    "NotoSansArmenian.ttf"
    "NotoSansBengali.ttf"
    "NotoSansEthiopic.ttf"
    "NotoSansGeorgian.ttf"
    "NotoSansGujarati.ttf"
    "NotoSansGurmukhi.ttf"
    "NotoSansKannada.ttf"
    "NotoSansKhmer.ttf"
    "NotoSansLao.ttf"
    "NotoSansMalayalam.ttf"
    "NotoSansMyanmar.ttf"
    "NotoSansSinhala.ttf"
    "NotoSansTamil.ttf"
    "NotoSansTelugu.ttf"
    "NotoSerifTibetan.ttf"
  ];
  plexFiles = 27;
  fonts = pkgs.runCommand "stt-bundle-fonts" {} ''
    mkdir -p $out/share/fonts/truetype
    for weight in Regular Medium SemiBold; do
      cp ${plex}/share/fonts/truetype/*-$weight.ttf $out/share/fonts/truetype/
    done
    for file in ${lib.escapeShellArgs notoScripts}; do
      cp ${pkgs.noto-fonts}/share/fonts/noto/"$file" $out/share/fonts/truetype/
    done
    count=$(ls $out/share/fonts/truetype | wc -l)
    if [ "$count" -ne ${toString (plexFiles + builtins.length notoScripts)} ]; then
      echo "expected ${toString (plexFiles + builtins.length notoScripts)} font files (9 Plex families x 3 weights + ${toString (builtins.length notoScripts)} Noto script faces), got $count" >&2
      exit 1
    fi

    # Both families are SIL Open Font License 1.1: each notice travels with the fonts it covers, as
    # the licence requires of every copy.
    mkdir -p $out/share/licenses/stt-bundle-fonts
    cp ${../frontend/src/shared/theme/fonts/OFL.txt} $out/share/licenses/stt-bundle-fonts/IBM-Plex-OFL.txt
    cp ${./fixtures/ofl-noto.txt} $out/share/licenses/stt-bundle-fonts/Noto-OFL.txt
  '';

  # The bundle's whole font configuration: only the fonts above, no host directories and no host
  # `conf.d` (the namespace sees the host's /etc). The fallback order decides Han glyph forms: the
  # transcript's own `lang` (item 74) selects the language's face, and a text with no language marking
  # falls back in this order - Simplified Chinese first, then Traditional, then Japanese.
  fontFamilies = [
    "IBM Plex Sans"
    "IBM Plex Sans Arabic"
    "IBM Plex Sans Devanagari"
    "IBM Plex Sans Hebrew"
    "IBM Plex Sans Thai"
    "IBM Plex Sans SC"
    "IBM Plex Sans TC"
    "IBM Plex Sans JP"
    "IBM Plex Sans KR"
  ];
  fontAlias = generic: ''
    <alias binding="same">
      <family>${generic}</family>
      <prefer>
        ${lib.concatMapStrings (family: "<family>${family}</family>") fontFamilies}
      </prefer>
    </alias>
  '';

  # Item 74: the Han face of each language the transcript can be marked with. Without these, a `zh`
  # transcript (whisper's code for Chinese) resolved to the JP face, since every Plex Han face covers
  # the shared characters and fontconfig's language score did not single out SC. Each rule prepends
  # the face strongly for its exact tag: `compare="eq"`, because `contains` lets a bare `zh` match
  # the Traditional tags too (measured with fontconfig 2.17). nix/font-forms.nix holds the mapping.
  hanFaces = {
    ja = "IBM Plex Sans JP";
    zh = "IBM Plex Sans SC";
    zh-cn = "IBM Plex Sans SC";
    zh-tw = "IBM Plex Sans TC";
    yue = "IBM Plex Sans TC";
  };
  hanFaceRule = language: face: ''
    <match target="pattern">
      <test name="lang" compare="eq"><string>${language}</string></test>
      <edit name="family" mode="prepend" binding="strong"><string>${face}</string></edit>
    </match>
  '';
  fontsConf = pkgs.writeText "stt-bundle-fonts.conf" ''
    <?xml version="1.0"?>
    <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
    <fontconfig>
      <dir>${fonts}/share/fonts</dir>
      <cachedir prefix="xdg">fontconfig</cachedir>
      ${lib.concatMapStrings fontAlias ["sans-serif" "sans" "system-ui"]}
      ${lib.concatStrings (lib.mapAttrsToList hanFaceRule hanFaces)}
    </fontconfig>
  '';
in {
  inherit fonts fontsConf;
}
