# The release asset of a build of this flake (roadmap items 56, 62 and 63): the build's output as a
# deterministic tar.zst plus the pin to paste into nix/desktop-artifacts.nix — the asset's sha256,
# the source build's out path and its direct references. The user builds the printer, uploads the
# tar to a release of this repository and records the printed pin; agents never publish.
#
# `name` is the printer's derivation name, `tag` the release the asset is uploaded to and `key` the
# pin's entry in nix/desktop-artifacts.nix.
{
  pkgs,
  source,
  name,
  tag,
  key,
}:
let
  system = pkgs.stdenv.hostPlatform.system;
  file = "${source.name}-${system}.tar.zst";
in
  pkgs.runCommand name {
    __structuredAttrs = true;
    nativeBuildInputs = [pkgs.zstd pkgs.coreutils pkgs.gnused pkgs.jq];
    exportReferencesGraph.graph = [source];
  } ''
    mkdir $out
    tar -cf - --hard-dereference --sort=name --mtime="@$SOURCE_DATE_EPOCH" --owner=0 --group=0 --mode=u+rw,uga+r -C ${source} . | zstd -19 -T0 -o $out/${file}
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
