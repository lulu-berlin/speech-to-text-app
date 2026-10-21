# whisper-cpp shared by devenv.nix (development) and flake.nix (packaging), like
# nix/models.nix: one build in both worlds (ADR 0036).
#
# Pinned to the v1.8.4 tag by a source override (item 33 consultation, 2026-10-11):
# the Gradle bootstrap downloads the jiang1997 1.8.4 prebuild, so the Nix build, the
# devenv and the Gradle path all run one version. Vulkan is the GPU path on any Linux
# host with a Vulkan driver. The tag comes from nix/versions.nix (one copy).
#
# nixpkgs' recipe is for 1.9.2, and two of its parts differ at 1.8.4: the install
# check also runs parakeet-cli, which 1.8.4 does not have, and its WHISPER_COMMON_FFMPEG
# option is still named WHISPER_FFMPEG there, so the flag is a no-op and whisper-cli is
# built without ffmpeg libs (it reads the backend's FLAC chunks through its bundled
# miniaudio). The source hash is the NAR hash of the v1.8.4 tarball.
#
# `withFFmpegSupport = false` (roadmap item 62): nixpkgs' recipe otherwise wraps the unused
# `bin/whisper-server` with nixpkgs' ffmpeg and adds it as a build input; whisper-cli needs
# neither. With it off, the only ffmpeg in every shipped closure is the app's pinned
# audio-only artifact.
#
# `vulkanSupport` on Linux and `metalSupport` on darwin (roadmap item 48): nixpkgs turns Metal on
# by default only for aarch64-darwin, so x86_64-darwin needs it explicitly. `withSDL = false` (found
# 2026-10-15 by `nix why-depends` on the desktop closure): the default builds whisper.cpp's SDL
# example too, which alone dragged SDL2 -> sdl2-compat -> sdl3 -> zenity -> libadwaita -> gtk4 ->
# gstreamer -> gst-plugins-bad -> flite, freepats, chromaprint, python3 and pipewire into the
# package — ~330 MiB for an example nothing runs. Only whisper-cli is used (ADR 0047), and it is
# not the SDL one.
#
# On x86_64-linux the shipped output is the pinned prebuilt release asset
# (nix/desktop-artifacts.nix `whisperCpp`, roadmap item 63), fetched and re-linked by
# nix/prebuilt-artifact.nix; a system without a pin ships the source build (items 48/49 add
# their systems' artifacts with them). `source` is the derivation the asset is made from,
# `whisperCpp` the one the package, Docker and the devenv run.
{ pkgs, artifacts ? import ./desktop-artifacts.nix }:
let
  tag = (import ./versions.nix).whisperCppTag;
  system = pkgs.stdenv.hostPlatform.system;

  source = (pkgs.whisper-cpp.override {
    vulkanSupport = pkgs.stdenv.hostPlatform.isLinux;
    metalSupport = pkgs.stdenv.hostPlatform.isDarwin;
    withSDL = false;
    withFFmpegSupport = false;
  }).overrideAttrs {
    version = tag;
    src = pkgs.fetchFromGitHub {
      owner = "ggml-org";
      repo = "whisper.cpp";
      tag = "v${tag}";
      hash = "sha256-YCuWKDKlrhbx+t3t2kDpAwuKt4rkipDsOXO6uqIU/W0=";
    };
    installCheckPhase = ''
      runHook preInstallCheck
      "$out/bin/whisper-cli" --help >/dev/null
      runHook postInstallCheck
    '';
  };

  # The pinned release asset for this system, if any: the shared helper fetches, re-links and
  # scans it; a system without a pin gets the source build.
  prebuiltArtifact = import ./prebuilt-artifact.nix {
    inherit pkgs source;
    artifact = artifacts.whisperCpp.${system} or null;
    label = "whisperCpp";
    subject = "whisper-cpp";
  };
in {
  inherit source;
  inherit (prebuiltArtifact) pinError;
  whisperCpp = prebuiltArtifact.result;
}
