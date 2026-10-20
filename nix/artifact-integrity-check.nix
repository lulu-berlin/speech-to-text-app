# The flake check for a prebuilt artifact's integrity gates (roadmap items 62 and 63): a registered
# asset that is missing or does not match its sha256 fails naming the asset, url and sha256; an asset
# pinned to another source build fails at evaluation naming both paths; the stray-path scan fails on
# an unregistered path; the real prebuilt names no old build path, no stray path, sits in its own
# runtime closure, and holds every pinned reference.
#
# `importWith` imports the artifact's module with an overridden `artifacts` argument, `attr` is the
# module's result attribute, `name` the check's derivation name (also the message prefix),
# `fixtureName` the bogus asset's file name and `staleStorePath` a store path of this artifact's name
# that is not this flake's source build.
{
  pkgs,
  importWith,
  attr,
  pin,
  name,
  fixtureName,
  staleStorePath,
}:
let
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;
  zeros = "0000000000000000000000000000000000000000000000000000000000000000";
  real = importWith (import ./desktop-artifacts.nix);
  source = real.source;
  artifact = real.${attr};
  fixture = pkgs.writeText fixtureName "not the release asset";
  registered = url:
    importWith {
      ${attr}.${system} = {
        inherit url;
        sha256 = zeros;
        storePath = builtins.unsafeDiscardStringContext source.outPath;
        references = [];
      };
    };
  cases = {
    mismatched = registered "file://${fixture}";
    missing = registered "file://${fixture}.missing";
  };
  expectFailure = label: module: let
    asset = module.${attr}.asset;
  in ''
    if out=$TMPDIR/${label} ${asset.fetch} 2> ${label}.err; then
      fail "the ${label} artifact was accepted"
    fi
    for expected in ${lib.escapeShellArgs [asset.name asset.url asset.sha256]}; do
      grep -qF -- "$expected" ${label}.err || fail "the ${label} artifact's error does not name $expected"
    done
  '';
  stalePin = {
    url = "file://${fixture}";
    sha256 = zeros;
    storePath = staleStorePath;
    references = [];
  };
  stale = importWith {
    ${attr}.${system} = stalePin;
  };
  evaluated = builtins.tryEval stale.${attr}.outPath;
  message = stale.pinError or "";
  named = map builtins.unsafeDiscardStringContext [
    (baseNameOf "file://${fixture}")
    "file://${fixture}"
    zeros
    staleStorePath
    source.outPath
  ];
  unnamed = lib.filter (expected: !lib.hasInfix expected message) named;
in
  assert lib.assertMsg (!evaluated.success)
  "${name}: a pin made from ${staleStorePath} evaluated to ${toString evaluated.value}";
  assert lib.assertMsg (unnamed == [])
  "${name}: the stale pin's error does not name ${lib.concatStringsSep ", " unnamed}; it reads: ${message}";
    pkgs.runCommand name {
      nativeBuildInputs = [pkgs.coreutils pkgs.gnugrep];
    } ''
      fail() {
        echo "${name}: $1" >&2
        exit 1
      }
      ${lib.concatStrings (lib.mapAttrsToList expectFailure cases)}
      mkdir -p rogue allowed
      printf '/nix/store/00000000000000000000000000000000-rogue\n' > rogue/x
      if stray=$(bash ${./store-path-scan.sh} rogue); then
        fail "the scan accepted the unregistered path"
      fi
      echo "$stray" | grep -qF '00000000000000000000000000000000-rogue' ||
        fail "the scan did not name the unregistered path: $stray"
      ref=/nix/store/11111111111111111111111111111111-reference
      printf '%s\n' "$ref" > allowed/x
      bash ${./store-path-scan.sh} allowed "$ref" || fail "the scan rejected a declared reference"
      if grep -rlaF ${lib.escapeShellArg pin.storePath} ${artifact}; then
        fail "the prebuilt still names ${pin.storePath}, the build it was made from"
      fi
      bash ${./store-path-scan.sh} ${artifact} ${lib.escapeShellArgs pin.references} ||
        fail "the prebuilt names a stray store path"
      closure=${pkgs.closureInfo {rootPaths = [artifact];}}/store-paths
      grep -qxF ${artifact} $closure ||
        fail "the prebuilt is not in its own runtime closure"
      for reference in ${lib.escapeShellArgs pin.references}; do
        grep -qxF "$reference" $closure ||
          fail "the prebuilt's closure is missing its pinned reference $reference"
      done
      echo ok > $out
    ''
