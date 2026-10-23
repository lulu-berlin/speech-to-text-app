# The producer check for a release asset (roadmap items 62, 63 and 69): the pin recorded for it must
# name this build (its store path and every direct reference) and the asset (its sha256 and release
# url), so a wrong or stale pin cannot be uploaded unnoticed. The asset's file name is the one the
# build gives it (`asset.file`, from nix/release-asset.nix: `.tar.zst` on Linux, `.tar.gz` on
# darwin, item 69), and the pin's url must name that file. Every failure names the asset file and
# the pin's url (item 69).
#
# `check` is the assertion derivation; `app` is its executable front door (`bin/<name>`), which the
# flake's `apps.<name>` exposes. The script names the check, so running the app builds the check and
# the source it asserts against, while `nix flake check` only evaluates the check. `pinError` is
# the pin gates' message (null when they pass) and `verifyAsset` the sha256 gate, so a flake check
# can exercise both with fixtures.
{
  pkgs,
  source,
  asset,
  pin,
  tag,
  key,
  name,
}: let
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;
  file = asset.file;
  # How every failure names the asset: its file and the pin's url.
  pinned = "${file} (${pin.url})";
  sourcePath = builtins.unsafeDiscardStringContext source.outPath;
  expectedReferences = map (p: builtins.unsafeDiscardStringContext "${p}") pin.references;
  # The pin gates need nothing built, so a stale pin fails at evaluation.
  pinError =
    if pin == null
    then "${name}: nix/desktop-artifacts.nix has no ${key}.${system} entry, so there is no pin to verify"
    else if pin.storePath != sourcePath
    then "${name}: the pin for ${pinned} has storePath ${pin.storePath}, not this build's ${sourcePath}"
    else if !lib.hasInfix "releases/download/${tag}/" pin.url
    then "${name}: the pin for ${pinned} is not on the ${tag} release"
    else if builtins.baseNameOf pin.url != file
    then "${name}: the pin's url ${pin.url} does not name the ${file} this build makes"
    else null;
  verifyAsset = pkgs.writeShellApplication {
    name = "${name}-verify-asset";
    runtimeInputs = [pkgs.coreutils];
    text = ''
      [ -s "$1" ] || {
        echo "${name}: the asset ${pinned} is missing" >&2
        exit 1
      }
      got=$(sha256sum "$1" | cut -d' ' -f1)
      [ "$got" = ${lib.escapeShellArg pin.sha256} ] || {
        echo "${name}: the pin's sha256 for ${pinned} is ${pin.sha256}, not the asset's $got" >&2
        exit 1
      }
    '';
  };
  check =
    pkgs.runCommand name {
      __structuredAttrs = true;
      nativeBuildInputs = [pkgs.jq];
      exportReferencesGraph.graph = [source];
    } ''
      ${lib.getExe verifyAsset} ${asset}/${file}
      jq -e --arg self ${lib.escapeShellArg sourcePath} \
        --argjson expected ${lib.escapeShellArg (builtins.toJSON expectedReferences)} \
        '.graph[] | select(.path == $self) | ([.references[] | select(. != $self)] | sort) == ($expected | sort)' \
        "$NIX_ATTRS_JSON_FILE" >/dev/null || {
        echo "${name}: the pin's references for ${pinned} are not this build's direct references" >&2
        exit 1
      }
      echo ok > $out
    '';
  checked = value:
    if pinError != null
    then throw pinError
    else value;
in {
  inherit pinError verifyAsset;
  check = checked check;
  app = checked (pkgs.writeShellApplication {
    inherit name;
    # Naming the check makes it an input: building this script builds it and runs its assertions.
    text = ''
      : ${check}
      echo "${name}: ok"
    '';
  });
}
