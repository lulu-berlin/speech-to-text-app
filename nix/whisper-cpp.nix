# whisper-cpp shared by devenv.nix (development) and flake.nix (packaging), like
# nix/models.nix: one build in both worlds (ADR 0036).
#
# Pinned to the v1.9.5 tag by a source override (roadmap item 90): nixpkgs' recipe is for 1.9.2,
# so the override moves the tag and keeps our flags. Vulkan is the GPU path on any Linux host with
# a Vulkan driver, Metal is the GPU path on Apple Silicon. The tag comes from nix/versions.nix
# (one copy) and is also the release tag's version (`whisper-cpp-1.9.5`, nix/release-asset.nix).
#
# At 1.9.x the nixpkgs recipe's install check covers the binaries that exist, so the 1.8.4-only
# install-check override is gone; the source hash is the NAR hash of the v1.9.5 tarball.
#
# `withFFmpegSupport = false` (roadmap item 62): nixpkgs' recipe otherwise wraps the unused
# `bin/whisper-server` with nixpkgs' ffmpeg and adds it as a build input; whisper-cli needs
# neither. With it off, the only ffmpeg in every shipped closure is the app's pinned
# audio-only artifact.
#
# `vulkanSupport` on Linux and `metalSupport` on darwin (roadmap item 48): the flake's darwin
# system is aarch64-darwin, where nixpkgs enables Metal by default; the flag keeps it explicit.
# `withSDL = false` (found
# 2026-10-15 by `nix why-depends` on the desktop closure): the default builds whisper.cpp's SDL
# example too, which alone dragged SDL2 -> sdl2-compat -> sdl3 -> zenity -> libadwaita -> gtk4 ->
# gstreamer -> gst-plugins-bad -> flite, freepats, chromaprint, python3 and pipewire into the
# package — ~330 MiB for an example nothing runs. Only whisper-cli is used (ADR 0047), and it is
# not the SDL one.
#
# On x86_64-linux the shipped output is the pinned prebuilt release asset
# (nix/desktop-artifacts.nix `whisperCpp`, roadmap item 63), fetched and re-linked by
# nix/prebuilt-artifact.nix; aarch64-darwin ships the pinned static Metal build (roadmap item 48's
# follow-up, the same asset the Gradle bootstrap downloads). A system without a pin ships the
# source build (item 49 adds its system's artifact with it). `source` is the derivation the Linux
# asset is made from, `whisperCpp` the one the package, Docker and the devenv run.
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
      hash = "sha256-Qqqpt+pvCQwSbyaBr1bdb7q+3LbELTcloHdiSikGPc4=";
    };
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
