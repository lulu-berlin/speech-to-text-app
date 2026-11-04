# Roadmap item 90 (test-list item 5): the whisper asset's release tag is derived from the manifest's
# whisper.cpp version, and every pin URL and Gradle download URL must sit on that tag as a
# `whisper-cpp-<version>-<nix system>.tar.gz`. Pure data: the caller renders `failures` (and the
# expected tag its fixture pin is measured against). One archive format on every system because the
# Gradle bootstrap cannot read zstd.
{
  lib,
  tag,
  version,
  pins,
  downloads,
}:
let
  expectedTag = "whisper-cpp-${version}";
  base = url: builtins.baseNameOf url;
  onTag = url: lib.hasInfix "/releases/download/${expectedTag}/" url;
  expectedFile = url:
    lib.hasPrefix "${expectedTag}-" (base url) && lib.hasSuffix ".tar.gz" (base url);

  failures =
    lib.optional (tag != expectedTag) "the release tag is ${tag}, not ${expectedTag}"
    ++ map (pin: "the pin ${base pin.url} is not on the ${expectedTag} release: ${pin.url}")
    (builtins.filter (pin: !(onTag pin.url)) pins)
    ++ map (pin: "the pin file ${base pin.url} is not a ${expectedTag}-*.tar.gz")
    (builtins.filter (pin: !(expectedFile pin.url)) pins)
    ++ map (url: "the download ${url} is not on the ${expectedTag} release")
    (builtins.filter (url: !(onTag url)) downloads)
    ++ map (url: "the download ${base url} is not a ${expectedTag}-*.tar.gz")
    (builtins.filter (url: !(expectedFile url)) downloads);
in {
  inherit expectedTag failures;
}