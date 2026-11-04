# Version metadata for the human Gradle bootstrap (roadmap item 33). Pure data with
# no nixpkgs dependency: nix/whisper-cpp.nix reads whisperCppTag from here, and the
# flake renders this file plus the nixpkgs-derived versions into the committed
# versions.json (see flake.nix). The flake's versions-sync check and
# `./gradlew checkVersions` fail on drift; regenerate with `nix run .#update-versions`.
let
  wcTag = "1.9.5";
  # FFmpeg as published by bytedeco on Maven Central: <ffmpeg version>-<javacpp version>.
  ffmpegJar = "8.1.2-1.5.14";
  ffmpegUrl = classifier: "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/${ffmpegJar}/ffmpeg-${ffmpegJar}-${classifier}-gpl.jar";
in {
  # The whisper.cpp version every route runs (roadmap item 90: the newest upstream tag). The Nix
  # build (nix/whisper-cpp.nix) compiles it, and the release assets we publish under the
  # version-named tag `whisper-cpp-${wcTag}` are the same build for the Gradle bootstrap, the
  # desktop packages and the installers; no third-party prebuild is downloaded.
  whisperCppTag = wcTag;

  # The platforms the app ships, as Nix system names (roadmap item 90): one vocabulary for the
  # download table below, the flake's systems, the Gradle bootstrap and the desktop staging.
  # Windows has no Nix package (it is outside the flake) but is a supported download-only platform;
  # Intel macOS is unsupported. A check holds every download key to this list.
  supportedPlatforms = ["x86_64-linux" "aarch64-darwin" "x86_64-windows"];

  # Prebuilt tools for machines without Nix (user decisions 2026-10-11/12), each pinned
  # by URL and sha256:
  # - whisper-cli: one build we publish for every shipped system under the tag
  #   `whisper-cpp-<version>` (roadmap item 90), the version-named tag in nix/release-asset.nix;
  #   the sha256 values are the pins the producer prints after the build.
  # - ffmpeg: bytedeco's build on Maven Central for Linux (user decision 2026-10-12), which never
  #   deletes or changes a published artifact - the BtbN daily build first pinned here was deleted
  #   after 14 days. The bytedeco jar is Central's 8.1.2; the Nix/DMG/Windows builds are 8.1.3
  #   after the nixpkgs bump (roadmap item 90). The jar holds a self-contained `ffmpeg`
  #   executable with its libav* libraries beside it (RUNPATH $ORIGIN). Central publishes SHA-1
  #   only: the sha256 below was computed over bytes that match Central's SHA-1. Apple Silicon
  #   (roadmap item 69) and Windows (roadmap item 68) get our own audio-only build instead, the
  #   same `.tar.gz` assets the flake/DMG and the Windows packages ship.
  downloads = {
    # All three are our own `whisper-cpp-${wcTag}` builds: the Linux Vulkan build (roadmap item 63),
    # the static Metal build (roadmap item 48; the upstream darwin tarballs omit the libggml-metal/
    # libggml-blas dylibs their binaries load) and the self-contained Windows build (roadmap item 49;
    # the upstream tarball imports the MSVC runtime and vulkan-1.dll, which a stock Windows install
    # lacks). The Windows asset is built with the static CRT, no OpenMP and the pinned Vulkan loader
    # beside the exes (the loader enumerates no devices without an ICD, which is the CPU fallback).
    "whisper-cli" = {
      # The portable glibc build from the manual `linux-whisper-asset` job: static whisper libraries
      # and the system Vulkan loader, so the Gradle bootstrap and the .deb run without Nix. The Nix
      # routes use the Nix source asset (nix/desktop-artifacts.nix), whose ELF interpreter and
      # RUNPATH name the Nix store and cannot run on a plain Linux host.
      "x86_64-linux" = {
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/whisper-cpp-${wcTag}/whisper-cpp-${wcTag}-x86_64-linux-glibc.tar.gz";
        sha256 = "ffab8189038ba21431795bc8a1373cfd672ae628d202823b8ccb9c9eb94ebcd0";
      };
      "aarch64-darwin" = {
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/whisper-cpp-${wcTag}/whisper-cpp-${wcTag}-aarch64-darwin.tar.gz";
        sha256 = "35c620d2430f4ca766e5771a5101dc4cc9b0785dbe3e1a73c889c2795fba162a";
      };
      # Windows x64: our self-contained build (roadmap item 49), whisper-cli.exe + whisper-server.exe
      # with the pinned Vulkan loader (vulkan-1.dll) beside them; built by the manual
      # `windows-whisper-asset` CI job and published under the version-named tag.
      "x86_64-windows" = {
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/whisper-cpp-${wcTag}/whisper-cpp-${wcTag}-x86_64-windows.tar.gz";
        sha256 = "86e3f0282f5a2055cf624174570d355ea82b08b8c2c8b6d2ff51d213c94df34b";
      };
    };
    ffmpeg = {
      "x86_64-linux" = {
        url = ffmpegUrl "linux-x86_64";
        sha256 = "3c56835c2675ed4fae638268588896a00e1dd109ccdea5aacc3056d6330a669e";
      };
      "aarch64-darwin" = {
        # Our own audio-only build (roadmap item 69): the same `.tar.gz` the flake pin, the DMG
        # and this bootstrap ship — one binary, system libraries only, no ffprobe.
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/ffmpeg-8.1.3/ffmpeg-audio-only-8.1.3-aarch64-darwin.tar.gz";
        sha256 = "d65ac745ec9a083bd478b999a4276b07717a4f225762aad3cc3ecb0376780390";
      };
      # Windows x64: our own audio-only build (roadmap item 68), one static ffmpeg.exe with only
      # system DLL imports and no ffprobe — the same enable list as Linux and macOS, built by the
      # manual `windows-ffmpeg-asset` job and published under ffmpeg-8.1.3.
      "x86_64-windows" = {
        url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/ffmpeg-8.1.3/ffmpeg-audio-only-8.1.3-x86_64-windows.tar.gz";
        sha256 = "6dc1e8536a2fb3ad5accee09fe3d0b0b6fb9af0a61221a8cde3be8da70b468bb";
      };
    };

    # The JDK 21 tarball whose jmods the desktop staging links with jlink (roadmap item 50):
    # the Temurin build of the flake's jdk version, so the jlink image runs without Nix — a
    # generic Linux image on Linux, a macOS image on darwin (roadmap item 48). The flake renders
    # `downloads` and `supportedPlatforms` into versions.json, so the same pins and platform keys
    # live there too.
    "temurin-jdk" = {
      "x86_64-linux" = {
        url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz";
        sha256 = "ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94";
      };
      "aarch64-darwin" = {
        url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz";
        sha256 = "3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998";
      };
      # Windows x64: a zip (Adoptium publishes no tar.gz for Windows), unpacked by the desktop staging.
      "x86_64-windows" = {
        url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip";
        sha256 = "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e";
      };
    };
  };
}
