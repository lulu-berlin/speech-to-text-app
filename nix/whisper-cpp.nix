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
{ pkgs }:
let
  tag = (import ./versions.nix).whisperCppTag;
in
(pkgs.whisper-cpp.override { vulkanSupport = true; }).overrideAttrs {
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
}