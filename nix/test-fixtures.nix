# Test-only audio fixtures, fetched by hash (never committed). Used by devenv.nix only, not by flake.nix.
{ pkgs }:
let
  # LibriSpeech test-clean (V. Panayotov, 2014), CC BY 4.0; attribution in docs/attributions.md.
  # Distributed only as this one 346 MB archive (md5 32fa31d27d2e1cad72775fee3f4849a9 as published by openslr).
  librispeechTestClean = pkgs.fetchurl {
    url = "https://www.openslr.org/resources/12/test-clean.tar.gz";
    sha256 = "39fde525e59672dc6d1551919b1478f724438a95aa55f874b576be21967e6c23";
  };
in
{
  # whisper.cpp samples/jfk.wav at release v1.9.2: 11 s JFK inaugural address excerpt,
  # a US-government work in the public domain (roadmap item 07). Exported as TEST_JFK_WAV.
  jfkWav = pkgs.fetchurl {
    url = "https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.9.2/samples/jfk.wav";
    sha256 = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e";
  };

  # Three unmodified LibriSpeech test-clean utterances (16 kHz mono 16-bit FLAC) for the speaker-embedding
  # tests of roadmap item 08: two of speaker 1089 from different chapters, one of speaker 1188 (both male).
  # Exported as TEST_LIBRISPEECH_CLIPS, a directory holding <utterance-id>.flac.
  librispeechClips = pkgs.runCommand "librispeech-test-clean-clips" { } ''
    mkdir -p $out
    tar -xzf ${librispeechTestClean} -C $out --strip-components=4 \
      LibriSpeech/test-clean/1089/134686/1089-134686-0002.flac \
      LibriSpeech/test-clean/1089/134691/1089-134691-0023.flac \
      LibriSpeech/test-clean/1188/133604/1188-133604-0025.flac
  '';
}
