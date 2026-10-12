# Test-only audio fixtures, fetched by hash. devenv.nix uses the derivations; `meta`
# re-exports the same table so the flake's versions.json render (roadmap item 33:
# `./gradlew e2e` fetches the same file without Nix) mirrors it without a second copy.
{ pkgs }:
let
  entries = {
    # whisper.cpp samples/jfk.wav at release v1.9.2: 11 s JFK inaugural address excerpt,
    # a US-government work in the public domain (roadmap item 07). Exported as TEST_JFK_WAV.
    jfkWav = {
      url = "https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.9.2/samples/jfk.wav";
      sha256 = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e";
    };
  };
in
builtins.mapAttrs (_: e: pkgs.fetchurl {inherit (e) url sha256;}) entries
// {meta = entries;}
