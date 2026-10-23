# The release asset of a build of this flake (roadmap items 56, 62 and 63): the build's output as a
# deterministic tar.zst plus the pin to paste into nix/desktop-artifacts.nix — the asset's sha256,
# the source build's out path and its direct references. The user builds the printer, uploads the
# tar to a release of this repository and records the printed pin; agents never publish.
#
# `name` is the printer's derivation name, `tag` the release the asset is uploaded to and `key` the
# pin's entry in nix/desktop-artifacts.nix. `compression` is `zst` (the Linux artifacts) or `gz`,
# for a darwin asset that a stock macOS host must be able to untar without zstd (roadmap item 69).
{
  pkgs,
  source,
  name,
  tag,
  key,
  compression ? "zst",
}:
let
  system = pkgs.stdenv.hostPlatform.system;
  file = "${source.name}-${system}.tar.${compression}";
  tarArgs = "--hard-dereference --sort=name --mtime=\"@$SOURCE_DATE_EPOCH\" --owner=0 --group=0 --mode=u+rw,uga+r -C ${source} .";
  pack =
    if compression == "zst"
    then "tar -cf - ${tarArgs} | zstd -19 -T0 -o $out/${file}"
    else "tar -cf - ${tarArgs} | gzip -9 -n > $out/${file}";
in
  pkgs.runCommand name {
    __structuredAttrs = true;
    nativeBuildInputs = [pkgs.coreutils pkgs.gnused pkgs.jq (if compression == "zst" then pkgs.zstd else pkgs.gzip)];
    exportReferencesGraph.graph = [source];
    # The name this build gives its asset, for the pin check (roadmap item 69): the pin's url must
    # name this file, and the file name must not come from the pin itself.
    passthru = {inherit file;};
  } ''
    mkdir $out
    ${pack}
    references=$(
      jq -r --arg self ${source} '.graph[] | select(.path == $self) | .references[] | select(. != $self)' $NIX_ATTRS_JSON_FILE
    )
    {
      echo "  ${key}.${system} = {"
      echo "    url = \"https://github.com/lulu-berlin/speech-to-text-app/releases/download/${tag}/${file}\";"
      echo "    sha256 = \"$(sha256sum $out/${file} | cut -d' ' -f1)\";"
      echo "    storePath = \"${source}\";"
      echo "    references = ["
      if [ -n "$references" ]; then echo "$references" | sed 's/^/      "/; s/$/"/'; fi
      echo "    ];"
      echo "  };"
    } > $out/pin.nix
    echo "asset: $out/${file}"
    cat $out/pin.nix
  ''
