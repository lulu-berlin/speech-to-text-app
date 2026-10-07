# Model assets shared by devenv.nix (development) and flake.nix (packaging).
# Hashes: docs/decisions/0008-default-whisper-model.md, docs/decisions/0003-kotlin-diarization-campplus-onnx.md
{ pkgs }:
{
  whisper = pkgs.fetchurl {
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin";
    sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";
  };

  speaker = pkgs.fetchurl {
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx";
    sha256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b";
  };

  # Integration-test fixture model (roadmap item 07), exported as TEST_WHISPER_TINY_EN_MODEL by devenv.nix.
  whisperTinyEn = pkgs.fetchurl {
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin";
    sha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f";
  };
}
