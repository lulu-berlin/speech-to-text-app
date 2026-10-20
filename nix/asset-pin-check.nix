# The producer check for a release asset (roadmap items 62 and 63): the pin recorded for it must
# name this build (its store path and every direct reference) and the asset (its sha256 and release
# url), so a wrong or stale pin cannot be uploaded unnoticed.
#
# `check` is the assertion derivation; `app` is its executable front door (`bin/<name>`), which the
# flake's `apps.<name>` exposes. The script names the check, so running the app builds the check and
# the source it asserts against, while `nix flake check` only evaluates the check.
{
  pkgs,
  source,
  asset,
  pin,
  tag,
  key,
  name,
}:
let
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;
  file = "${source.name}-${system}.tar.zst";
  sourcePath = builtins.unsafeDiscardStringContext source.outPath;
  expectedReferences = map (p: builtins.unsafeDiscardStringContext "${p}") pin.references;
  check =
    pkgs.runCommand name {
      __structuredAttrs = true;
      nativeBuildInputs = [pkgs.coreutils pkgs.jq];
      exportReferencesGraph.graph = [source];
    } ''
      fail() {
        echo "${name}: $1" >&2
        exit 1
      }
      [ -s ${asset}/${file} ] || fail "the asset ${file} is missing"
      got=$(sha256sum ${asset}/${file} | cut -d' ' -f1)
      [ "$got" = ${lib.escapeShellArg pin.sha256} ] ||
        fail "the pin's sha256 is not the asset's $got"
      ${pkgs.jq}/bin/jq -e --arg self ${lib.escapeShellArg sourcePath} \
        --argjson expected ${lib.escapeShellArg (builtins.toJSON expectedReferences)} \
        '.graph[] | select(.path == $self) | ([.references[] | select(. != $self)] | sort) == ($expected | sort)' \
        $NIX_ATTRS_JSON_FILE >/dev/null ||
        fail "the pin's references are not this build's direct references"
      echo ok > $out
    '';
in
  # The pin is the data nix/desktop-artifacts.nix records; these are the gates that need nothing
  # built, so a stale pin fails at evaluation.
  assert lib.assertMsg (pin.storePath == sourcePath)
  "${name}: ${key}.${system}.storePath is ${pin.storePath}, not this build's ${sourcePath}";
  assert lib.assertMsg (baseNameOf pin.url == file)
  "${name}: the pin's url does not name ${file}";
  assert lib.assertMsg (lib.hasInfix "releases/download/${tag}/" pin.url)
  "${name}: the pin's url is not on the ${tag} release";
    {
      inherit check;
      app = pkgs.writeShellScriptBin name ''
        # Naming the check makes it an input: building this script builds it and runs its assertions.
        : ${check}
        echo "${name}: ok"
      '';
    }
