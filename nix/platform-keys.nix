# Roadmap item 90 (test-list item 11): the download table's platform keys are Nix system names
# (`x86_64-linux`, `aarch64-darwin`, `x86_64-windows`), the vocabulary the flake, the Gradle
# bootstrap and the desktop staging share — not the hand-minted `<os>-<arch>` names (`linux-x64`).
# A key must be a supported platform and a Nix double, every supported platform must be one the
# flake builds for, and every supported platform must pin every downloaded tool. Pure data with no
# nixpkgs dependency: the caller renders `errors`; `ok` lets a check assert the real table.
{
  lib,
  downloads,
  supportedPlatforms,
  flakeSystems,
  downloadOnlyPlatforms ? [],
}:
let
  doubles = lib.concatLists (lib.attrValues lib.systems.doubles);
  tools = builtins.attrNames downloads;
  platformKeys = lib.unique (lib.concatMap (tool: builtins.attrNames downloads.${tool}) tools);
  joined = platform: lib.concatStringsSep ", " platform;

  unsupportedKeys =
    builtins.filter (key: !(builtins.elem key supportedPlatforms)) platformKeys;
  nonDoubles = builtins.filter (platform: !(builtins.elem platform doubles)) supportedPlatforms;
  shipped = flakeSystems ++ downloadOnlyPlatforms;
  unsupportedFlakeSystems =
    builtins.filter (platform: !(builtins.elem platform supportedPlatforms)) flakeSystems;
  notShipped =
    builtins.filter (platform: !(builtins.elem platform shipped)) supportedPlatforms;
  missingPins =
    builtins.concatMap (platform:
      builtins.concatMap (tool:
        lib.optional (!(downloads.${tool} ? ${platform})) "${tool} has no ${platform} pin")
      tools)
    supportedPlatforms;

  errors =
    map (key: "downloads key ${key} is not a supported platform (${joined supportedPlatforms})") unsupportedKeys
    ++ map (platform: "supported platform ${platform} is not a Nix system name") nonDoubles
    ++ map (platform: "the flake ships ${platform}, which is not a supported platform") unsupportedFlakeSystems
    ++ map (platform: "supported platform ${platform} is not shipped by the flake and is not download-only") notShipped
    ++ missingPins;
in {
  inherit errors;
  ok = errors == [];
}