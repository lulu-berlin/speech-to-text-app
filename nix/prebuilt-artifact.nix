# The shared prebuilt-release-artifact mechanism (roadmap items 56 and 62; item 63 reuses it): a
# versioned GitHub Release asset of a build of this flake, pinned by url, sha256, the source build's
# out path and that output's direct references.
#
# A registered artifact is fetched by a step of our own, so a missing asset or one that does not
# match its pin fails naming the asset, its url and its sha256 (the fixed-output hash Nix checks
# afterwards is the second gate). Then it is unpacked and re-linked: the asset was built at another
# store path, which the output's files may name, and every occurrence of it becomes this output's
# own path (same length; both derivations use `source.name`). Each pinned direct reference is made
# an input, so the output's closure is complete, and the build fails if any other store path
# survives. It never depends on `source`: a configured artifact does not fall back to compiling.
# A registered asset is valid only for the source build it was made from; when this flake's source
# has another out path (nixpkgs bumped, the recipe edited), evaluation fails before anything is
# fetched or compiled.
#
# The caller supplies `label` (the key in nix/desktop-artifacts.nix, for messages), `subject`
# (the artifact's name in the stale-pin sentence) and `extraAttrs` for derivation-specific rules
# (Mesa's no-LLVM check).
{
  pkgs,
  source,
  artifact ? null,
  label,
  subject,
  extraAttrs ? {},
}: let
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;

  pin = artifact;
  assetName = builtins.unsafeDiscardStringContext (baseNameOf pin.url);

  # A pin's paths are plain strings in nix/desktop-artifacts.nix, so nothing would make them inputs.
  # Nix records as references only the store paths that are inputs of a derivation, so without the
  # attached `path` context the libraries the output loads are dropped from its closure — and from
  # the bundle's payload — while the files still name them. With it, the build depends on them and
  # the closure is complete.
  referenced = map (p: builtins.appendContext p {"${p}" = {path = true;};}) pin.references;

  # A registered artifact: the release asset, then its unpack and re-link. The asset was built at
  # another store path, and the output may name that path, so every occurrence of it becomes this
  # output's own path (same length). Each pinned direct reference is made an input, so the output's
  # closure is complete, and the build fails if any other store path survives. A static asset
  # (storePath = null, the darwin whisper-cli) names no store path at all: there is nothing to
  # rewrite, and the stray-path scan below still proves it.
  prebuilt = let
    name = assetName;
    tools = pkgs.buildPackages;
    fetch = tools.writeShellScript "fetch-${name}" ''
      # Structured attrs pass the derivation's attributes as JSON/shell files, not environment
      # variables: in a build the output path comes from the shell file's `outputs` map ($out is
      # unset otherwise — item 63's first fetch handed curl an empty --output). A direct invocation
      # (the integrity checks) has no such file and keeps the caller's $out.
      if [ -n "''${NIX_ATTRS_SH_FILE:-}" ]; then
        source "$NIX_ATTRS_SH_FILE"
        out=''${outputs[out]}
      fi
      export PATH=${lib.makeBinPath [tools.curl tools.coreutils]}
      export SSL_CERT_FILE=${tools.cacert}/etc/ssl/certs/ca-bundle.crt
      name=${lib.escapeShellArg name}
      url=${lib.escapeShellArg pin.url}
      sha256=${lib.escapeShellArg pin.sha256}
      fail() {
        echo "desktop artifact $name (${label}.${system} in nix/desktop-artifacts.nix): $1" >&2
        echo "  url:    $url" >&2
        echo "  sha256: $sha256" >&2
        exit 1
      }
      if ! error=$(curl --fail --silent --show-error --location --retry 3 --output "$out" "$url" 2>&1); then
        fail "missing: $error"
      fi
      got=$(sha256sum "$out" | cut -d' ' -f1)
      if [ "$got" != "$sha256" ]; then
        fail "does not match its pin: got sha256 $got"
      fi
    '';
    asset =
      derivation {
        inherit name;
        # A flat fixed-output derivation's bytes are arbitrary (the tar names the build's own path
        # and its libraries), so it has no meaningful references; newer Nix scans every built output
        # and rejects references on a flat FOD unless they are discarded (item 63's first fetch:
        # "fixed output derivation '…' is not allowed to refer to other store paths"). Structured
        # attrs are how the option reaches the low-level `derivation` builtin.
        __structuredAttrs = true;
        unsafeDiscardReferences = {out = true;};
        system = pkgs.stdenv.buildPlatform.system;
        builder = "${fetch}";
        outputHashMode = "flat";
        outputHashAlgo = "sha256";
        outputHash = pin.sha256;
        impureEnvVars = lib.fetchers.proxyImpureEnvVars;
        preferLocalBuild = true;
      }
      // {
        inherit fetch;
        inherit (pin) url sha256;
      };
  in
    pkgs.runCommand source.name ({
      nativeBuildInputs = [tools.zstd tools.gzip tools.perl tools.coreutils tools.gnugrep];
      passthru = {inherit asset;};
    } // extraAttrs) ''
      mkdir $out
      case ${lib.escapeShellArg assetName} in
        *.tar.zst) tar -x --zstd -f ${asset} -C $out ;;
        *.tar.gz) tar -x --gzip -f ${asset} -C $out ;;
        *)
          echo "desktop artifact ${name}: unsupported asset name ${assetName}" >&2
          exit 1
          ;;
      esac
      ${lib.optionalString (pin.storePath != null) ''
        # The files that name the asset's build path get this output's path instead; the scan below
        # proves none was missed.
        old=${lib.escapeShellArg pin.storePath}
        files=$(grep -rlF -- "$old" $out || true)
        # `old` must reach perl's environment (a shell variable is not visible in %ENV); without it
        # the empty pattern rewrites every position.
        if [ -n "$files" ]; then old="$old" out="$out" perl -pi -e 's/\Q$ENV{old}\E/$ENV{out}/g' $files; fi
        if [ -n "$files" ] && grep -qF -- "$old" $files; then
          echo "desktop artifact ${name}: these files still name ${pin.storePath}" >&2
          exit 1
        fi
      ''}
      if ! stray=$(bash ${./store-path-scan.sh} $out ${lib.escapeShellArgs referenced}); then
        echo "desktop artifact ${name}: names store paths that are neither itself nor a pinned reference:" >&2
        echo "$stray" >&2
        exit 1
      fi
    '';

  # A registered asset is only valid for the source build it was made from: its files name that
  # build's libraries and its own store path. When this flake's source has another out path,
  # evaluation fails here, before anything is fetched or compiled. The text is exposed for the
  # flake's check, which cannot read an evaluation error.
  sourcePath = builtins.unsafeDiscardStringContext source.outPath;
  pinError =
    if pin == null || pin.storePath == null || pin.storePath == sourcePath
    then null
    else ''
      desktop artifact ${assetName} (${label}.${system} in nix/desktop-artifacts.nix) was made from another ${subject} build than this flake's; rebuild the asset from this flake's ${subject} and pin the new release.
        url:       ${builtins.unsafeDiscardStringContext pin.url}
        sha256:    ${pin.sha256}
        storePath: ${pin.storePath}
        this flake's ${subject}: ${sourcePath}
    '';
in {
  inherit pin assetName pinError;
  result =
    if pin == null
    then source
    else if pinError != null
    then throw pinError
    else prebuilt;
}
