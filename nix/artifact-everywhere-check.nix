# The flake check that the shipped graphs carry one prebuilt artifact (roadmap items 62 and 63): the
# pin is registered, the shipped import is not the source build, the desktop's tools and the server
# wrapper resolve to the artifact, and each named closure holds it and not the source build. The
# source path is context-free on purpose: referencing the build pulls it into this check (item 62's
# lesson).
#
# `key` is the pin's entry in nix/desktop-artifacts.nix, `subject` the artifact's name in messages,
# `tool` the program under the desktop's resources/tools/bin and the wrapper's PATH, and `name` the
# check's derivation name (also the message prefix).
{
  pkgs,
  artifact,
  source,
  pinned,
  key,
  subject,
  tool,
  desktop,
  server,
  roots,
  name,
}:
let
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;
  sourcePath = builtins.unsafeDiscardStringContext source.outPath;
  closures = lib.mapAttrs (_: root: pkgs.closureInfo {rootPaths = [root];}) roots;
in
  assert lib.assertMsg (pinned != null)
  "${name}: nix/desktop-artifacts.nix has no ${key}.${system} entry, so the ${subject} is a source build";
  assert lib.assertMsg (artifact.outPath != source.outPath)
  "${name}: the shipped ${subject} is the source build ${sourcePath}, not the prebuilt asset";
    pkgs.runCommand name {
      nativeBuildInputs = [pkgs.coreutils pkgs.gnugrep];
    } ''
      fail() {
        echo "${name}: $1" >&2
        exit 1
      }
      desktopTool=$(readlink -f ${desktop}/share/stt-app/resources/tools/bin/${tool})
      [ "$desktopTool" = ${artifact}/bin/${tool} ] ||
        fail "the desktop's tools ${tool} is $desktopTool, not ${artifact}/bin/${tool}"
      grep -qF ${artifact}/bin ${server}/bin/stt-app ||
        fail "the server package's wrapper does not name ${artifact}/bin"
      ${lib.concatStrings (lib.mapAttrsToList (rootName: closure: ''
        grep -qxF ${artifact} ${closure}/store-paths ||
          fail "the ${rootName} closure does not hold ${artifact}"
        if grep -qxF ${sourcePath} ${closure}/store-paths; then
          fail "the ${rootName} closure holds the source build ${sourcePath}"
        fi
      '') closures)}
      echo ok > $out
    ''
