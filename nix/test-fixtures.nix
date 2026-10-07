# Test-only audio fixtures, fetched by hash (never committed). Used by devenv.nix only, not by flake.nix.
{ pkgs }:
{
  # whisper.cpp samples/jfk.wav at release v1.9.2: 11 s JFK inaugural address excerpt,
  # a US-government work in the public domain (roadmap item 07). Exported as TEST_JFK_WAV.
  jfkWav = pkgs.fetchurl {
    url = "https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.9.2/samples/jfk.wav";
    sha256 = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e";
  };
}
