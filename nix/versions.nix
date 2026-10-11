# Version metadata for the human Gradle bootstrap (roadmap item 33). Pure data with
# no nixpkgs dependency: nix/whisper-cpp.nix reads whisperCppTag from here, and the
# flake renders this file plus the nixpkgs-derived versions into the committed
# versions.json (see flake.nix). The flake's versions-sync check and
# `./gradlew checkVersions` fail on drift; regenerate with `nix run .#update-versions`.
let
  wcTag = "1.8.4";
in {
  # The whisper.cpp release tag the Nix build (nix/whisper-cpp.nix) and the Gradle
  # bootstrap's prebuilt whisper-cli both run (user decision 2026-10-11: upstream
  # ggml-org has no release binaries, so the Gradle path downloads the
  # jiang1997/whisper.cpp-release prebuild pinned to the same tag).
  whisperCppTag = wcTag;

  # Prebuilt tools for machines without Nix (user decision 2026-10-11): whisper-cli
  # (jiang1997 prebuild, same tag as the Nix build; Vulkan on Linux, CoreML on
  # macOS), ffmpeg static builds on the 8.1 branch. Every entry is pinned by URL and
  # sha256; all hashes were verified against the sources' published checksums
  # (whisper-cli: computed over the GitHub release assets; ffmpeg linux-x64: the
  # BtbN checksums.sha256 of the immutable autobuild release; ffmpeg darwin-arm64:
  # ffmpeg.martin-riedl.de's ffmpeg.zip.sha256). macOS Intel ships no published
  # static 8.1 ffmpeg (evermeet prunes old releases; martin-riedl builds arm64
  # only), so darwin-x64 has no entry - the README documents that gap.
  downloads = {
    # The whisper-cli URLs are constructed from whisperCppTag (one copy of the tag).
    "whisper-cli" = {
      "linux-x64" = {
        url = "https://github.com/jiang1997/whisper.cpp-release/releases/download/v${wcTag}/whisper-${wcTag}-linux-x64.tar.gz";
        sha256 = "d89e59ee9a0acd4bcadaadd31edcb60a079a6929a3e06293c1cca7751d3563ed";
      };
      "darwin-arm64" = {
        url = "https://github.com/jiang1997/whisper.cpp-release/releases/download/v${wcTag}/whisper-${wcTag}-macos-arm64.tar.gz";
        sha256 = "44eca18e0cfa74e856d589fe6da70ce251aa18b04417766828b3f89ad7934921";
      };
      "darwin-x64" = {
        url = "https://github.com/jiang1997/whisper.cpp-release/releases/download/v${wcTag}/whisper-${wcTag}-macos-x64.tar.gz";
        sha256 = "a5f41b14a6e4cb3e0c045b7954f0a4050bc0d44a9b2ce2e5826ee2a5fef8577c";
      };
    };
    ffmpeg = {
      "linux-x64" = {
        url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-09-27-13-04/ffmpeg-n8.1.3-2-g45e8e0a3ff-linux64-gpl-8.1.tar.xz";
        sha256 = "36d914fd29629f170a39879dccacb8e9d6ba6ee5fdbb922a43f88fc03bdccfb3";
      };
      "darwin-arm64" = {
        url = "https://ffmpeg.martin-riedl.de/download/macos/arm64/1783011502_8.1.2/ffmpeg.zip";
        sha256 = "ef1aa60006c7b77ce170c1608c08d8e4ba1c30c5746f2ac986ded932d0ac2c3c";
      };
    };
  };
}