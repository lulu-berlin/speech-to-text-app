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
  # - whisper-cli: linux-x64 and darwin-x64 are the jiang1997 prebuild of the Nix build's tag;
  #   darwin-arm64 and windows-x64 are our own `desktop-whisper-cpp-v1` builds (roadmap items 48
  #   and 49). Hashes computed over the GitHub release assets.
  # - ffmpeg: bytedeco's builds on Maven Central for Linux and Intel macOS (user decision
  #   2026-10-12), which never deletes or changes a published artifact - the BtbN daily build
  #   first pinned here was deleted after 14 days. 8.1.2 is the flake's ffmpeg_8. Each jar holds
  #   a self-contained `ffmpeg` executable with its libav* libraries beside it (RUNPATH $ORIGIN
  #   on Linux, @rpath = @loader_path on macOS). Central publishes SHA-1 only: the sha256 below
  #   was computed over bytes that match Central's SHA-1. Apple Silicon gets our own audio-only
  #   build instead (roadmap item 69), the same `.tar.gz` asset the flake and the DMG ship.
  downloads = {
    # linux-x64 and darwin-x64 come from the upstream jiang1997 tag. darwin-arm64 is our own
    # `desktop-whisper-cpp-v1` static build: the upstream darwin tarballs omit the libggml-metal/
    # libggml-blas dylibs their binaries load, so whisper-cli cannot start there (roadmap item 48).
    "whisper-cli" = {
      "linux-x64" = {
        url = "https://github.com/jiang1997/whisper.cpp-release/releases/download/v${wcTag}/whisper-${wcTag}-linux-x64.tar.gz";
        sha256 = "d89e59ee9a0acd4bcadaadd31edcb60a079a6929a3e06293c1cca7751d3563ed";
      };
      "darwin-arm64" = {
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-whisper-cpp-v1/whisper-cpp-1.8.4-aarch64-darwin.tar.gz";
        sha256 = "58ff90b3b693c58c28a3cf50c8dc9fcc6ad355101e9535f234d187bd9651078a";
      };
      "darwin-x64" = {
        url = "https://github.com/jiang1997/whisper.cpp-release/releases/download/v${wcTag}/whisper-${wcTag}-macos-x64.tar.gz";
        sha256 = "a5f41b14a6e4cb3e0c045b7954f0a4050bc0d44a9b2ce2e5826ee2a5fef8577c";
      };
      # Windows x64: static whisper-cli.exe with ggml-vulkan (roadmap item 49); no DLLs beside it.
      "windows-x64" = {
        url = "https://github.com/jiang1997/whisper.cpp-release/releases/download/v${wcTag}/whisper-${wcTag}-windows-x64.tar.gz";
        sha256 = "cfc2a9f82e6db9c5fe11e3637d6be8660ae169a527febfbc5d35a5e0d96ca85b";
      };
    };
    ffmpeg = {
      "linux-x64" = {
        url = ffmpegUrl "linux-x86_64";
        sha256 = "3c56835c2675ed4fae638268588896a00e1dd109ccdea5aacc3056d6330a669e";
      };
      "darwin-arm64" = {
        # Our own audio-only build (roadmap item 69): the same `.tar.gz` the flake pin, the DMG
        # and this bootstrap ship — one binary, system libraries only, no ffprobe.
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-ffmpeg-v2/ffmpeg-audio-only-8.1.2-aarch64-darwin.tar.gz";
        sha256 = "9f0c576d370ed47da5a541f7021e21a8a6916c2949a5546cbab7d08528a2b313";
      };
      "darwin-x64" = {
        url = ffmpegUrl "macosx-x86_64";
        sha256 = "917327cd6178e7ecddd4fb9dd5981cb4c5f04631a06dc0bd81cfe0377b1f3705";
      };
      # Windows x64: the same bytedeco jar shape as Linux, with ffmpeg.exe and its DLLs (item 49).
      "windows-x64" = {
        url = ffmpegUrl "windows-x86_64";
        sha256 = "9c4bbdecba752fb2d0a8e537e69d330c6db6b02087f5035f88decad0050e5550";
      };
    };

    # The JDK 21 tarball whose jmods the desktop staging links with jlink (roadmap item 50):
    # the Temurin build of the flake's jdk version, so the jlink image runs without Nix — a
    # generic Linux image on Linux, a macOS image on darwin (roadmap item 48). The flake renders
    # `downloads` (and only that) into versions.json, so the same pins live there too.
    "temurin-jdk" = {
      "linux-x64" = {
        url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz";
        sha256 = "ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94";
      };
      "darwin-arm64" = {
        url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz";
        sha256 = "3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998";
      };
      # Windows x64: a zip (Adoptium publishes no tar.gz for Windows), unpacked by the desktop staging.
      "windows-x64" = {
        url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip";
        sha256 = "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e";
      };
    };
  };
}
