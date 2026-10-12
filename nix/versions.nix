# Version metadata for the human Gradle bootstrap (roadmap item 33). Pure data with
# no nixpkgs dependency: nix/whisper-cpp.nix reads whisperCppTag from here, and the
# flake renders this file plus the nixpkgs-derived versions into the committed
# versions.json (see flake.nix). The flake's versions-sync check and
# `./gradlew checkVersions` fail on drift; regenerate with `nix run .#update-versions`.
let
  wcTag = "1.8.4";
  # FFmpeg as published by bytedeco on Maven Central: <ffmpeg version>-<javacpp version>.
  ffmpegJar = "8.1.2-1.5.14";
  ffmpegUrl = classifier: "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/${ffmpegJar}/ffmpeg-${ffmpegJar}-${classifier}-gpl.jar";
in {
  # The whisper.cpp release tag the Nix build (nix/whisper-cpp.nix) and the Gradle
  # bootstrap's prebuilt whisper-cli both run (user decision 2026-10-11: upstream
  # ggml-org has no release binaries, so the Gradle path downloads the
  # jiang1997/whisper.cpp-release prebuild pinned to the same tag).
  whisperCppTag = wcTag;

  # Prebuilt tools for machines without Nix (user decisions 2026-10-11/12), each pinned
  # by URL and sha256:
  # - whisper-cli: the jiang1997 prebuild of the Nix build's tag (Vulkan on Linux,
  #   CoreML on macOS); hashes computed over the GitHub release assets.
  # - ffmpeg: bytedeco's builds on Maven Central (user decision 2026-10-12), which
  #   never deletes or changes a published artifact - the BtbN daily build first pinned
  #   here was deleted after 14 days. 8.1.2 is the flake's ffmpeg_8. Each jar holds a
  #   self-contained `ffmpeg` executable with its libav* libraries beside it (RUNPATH
  #   $ORIGIN on Linux, @rpath = @loader_path on macOS). Central publishes SHA-1 only:
  #   the sha256 below was computed over bytes that match Central's SHA-1.
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
        url = ffmpegUrl "linux-x86_64";
        sha256 = "3c56835c2675ed4fae638268588896a00e1dd109ccdea5aacc3056d6330a669e";
      };
      "darwin-arm64" = {
        url = ffmpegUrl "macosx-arm64";
        sha256 = "03abefca5abf608f040da235a83f22ad32518ed4780c8dac724f940015d653bd";
      };
      "darwin-x64" = {
        url = ffmpegUrl "macosx-x86_64";
        sha256 = "917327cd6178e7ecddd4fb9dd5981cb4c5f04631a06dc0bd81cfe0377b1f3705";
      };
    };
  };
}
