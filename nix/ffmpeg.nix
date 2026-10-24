# The app's audio-only ffmpeg (roadmap items 62 and 69): one binary built for exactly the
# backend's ffmpeg surface, so `nix/desktop-artifacts.nix` can pin a release asset of it and the
# desktop, the server package, Docker and the devenv ship the same bytes without compiling ffmpeg.
#
# `source` is the build the asset is made from, on both systems. Its name is the final, re-linked
# output's name too (`source.name`; on Linux pkgsStatic adds `-static-<host>`:
# `ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2`). The asset is built at another store
# path, which the binary's own `configuration:` line names, and the re-link rewrites it in place —
# same-length paths, so both derivations use this exact name.
#
# On Linux the build is static (pkgsStatic's musl); darwin has no static libc, so the same recipe
# runs on the native package set there and the binary links its av* code in, naming only the OS's
# own libraries (roadmap item 69).
#
# The feature list is the app's, not nixpkgs': `--disable-everything` and then the demuxers of the 19
# accepted extensions' containers plus `concat` and `loas` (LATM), every native audio decoder those
# containers can carry — AAC including LATM, Opus, Vorbis, MP3/MP2/MP1, FLAC, ALAC, AC-3/E-AC-3,
# WMA v1/v2/Pro/Lossless/Voice, AMR, Speex, DTS, TrueHD/MLP, ATRAC, ADPCM, G.722 and the PCM family
# in every bit depth/endianness the containers map — the encoder each output muxer needs (FLAC; PCM
# for `f32le` and the null muxer's `-f null -`), the `silencedetect`/`atrim`/`aresample`/`aformat`
# filters, and the `file`/`pipe` protocols. No video codecs, hwaccels, capture devices, network
# protocols or external libraries (the `lib*` decoders stay off). The `ffmpeg-audio-only` app
# (`nix run .#ffmpeg-audio-only`) compares this exact surface; the format and app-use checks prove
# the behavior.
{
  pkgs,
  artifacts ? import ./desktop-artifacts.nix,
}: let
  # `pkgsStatic` is the musl cross package set and exists only on Linux; darwin has no static
  # libc, so the same audio-only recipe is built with the native package set there (roadmap item
  # 69). A system with a pinned release asset fetches it; one without builds from source.
  base = if pkgs.stdenv.hostPlatform.isLinux then pkgs.pkgsStatic else pkgs;
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;
  # ffmpeg's configure target: Linux keeps the pinned asset's exact flags; darwin needs its own
  # target, or configure cross-compiles for linux/x86_64 with the wrong compiler (roadmap item 69).
  targetOS = if pkgs.stdenv.hostPlatform.isDarwin then "darwin" else "linux";
  targetArch = if pkgs.stdenv.hostPlatform.isAarch64 then "aarch64" else "x86_64";

  # The component lists the build's `--enable-<kind>=…` flags come from, and the same lists the
  # `ffmpeg-audio-only` app compares the built configuration against: one source, so the check cannot
  # drift from the recipe. The data lives in nix/ffmpeg-enable.json (roadmap item 68), because the
  # Windows build has no Nix: the manual `windows-ffmpeg-asset` job renders the same component sets
  # and flags from it, so all three platforms build from one list.
  enable = (builtins.fromJSON (builtins.readFile ./ffmpeg-enable.json)).enable;

  # One recipe for both systems (roadmap item 69): the same enable list and configure flags; only
  # `base` differs (a static musl package set on Linux, the native set on darwin, which has no
  # static libc). The darwin build links its av* code in and names only the OS's libraries.
  source =
    (base.ffmpeg_8-headless.override {
      # The external libraries and GUI/network features all default on with the headless variant.
      withHeadlessDeps = false;
      withSmallDeps = false;
      withFullDeps = false;
      withGPL = false;
      withVersion3 = false;
      withNetwork = false;
      withDocumentation = false;
      # The libraries the ffmpeg program needs; avdevice (capture) is off.
      buildFfmpeg = true;
      buildFfprobe = false;
      buildFfplay = false;
      buildQtFaststart = false;
      buildAvcodec = true;
      buildAvformat = true;
      buildAvfilter = true;
      buildAvutil = true;
      buildSwresample = true;
      buildSwscale = true;
      buildAvdevice = false;
    }).overrideAttrs (old: {
      pname = "ffmpeg-audio-only";
      outputs = ["out"];
      # One output, and only the program in it: `make install` would also install the static
      # libraries and headers, which neither the app nor the asset needs.
      installPhase = ''
        runHook preInstall
        make install-progs
        runHook postInstall
      '';
      # FATE's check suite is for a full build; a slim one has no tests to run.
      doCheck = false;
      # Keep the configure line's prefix: it is the build path the asset's re-link rewrites. Strip the
      # cross build's host compiler instead — it is a build input, and Nix would record it in the
      # binary's references (nixpkgs' own postConfigure does this too).
      postConfigure = ''
        remove-references-to -t ${base.buildPackages.stdenv.cc} config.h
      '';
      # nixpkgs' postFixup patches the shared libraries' RUNPATH; this build has none.
      postFixup = "";
      configureFlags = [
        "--target_os=${targetOS}"
        "--arch=${targetArch}"
        "--prefix=${placeholder "out"}"
        "--disable-everything"
        "--disable-autodetect"
        "--enable-static"
        "--disable-shared"
        "--enable-pic"
        "--enable-pthreads"
        "--disable-os2threads"
        "--disable-debug"
        "--disable-stripping"
        "--enable-ffmpeg"
        "--disable-ffprobe"
        "--disable-ffplay"
        "--disable-doc"
        "--disable-network"
        "--disable-avdevice"
      ]
      ++ map ({kind, components}: "--enable-${kind}=${lib.concatStringsSep "," components}") enable
      # This list replaces nixpkgs', which is where a clang stdenv's compiler flags live. Without
      # them configure falls back to its `gcc` default, which a darwin stdenv does not provide
      # ("gcc is unable to create an executable file"). Linux's static build uses gcc and is
      # unaffected, so its flags and out path stay as they are.
      ++ lib.optionals base.stdenv.cc.isClang [
        "--cc=${base.stdenv.cc.targetPrefix}clang"
        "--cxx=${base.stdenv.cc.targetPrefix}clang++"
      ]
      ++ lib.optionals (base.stdenv.hostPlatform != base.stdenv.buildPlatform) [
        "--cross-prefix=${base.stdenv.cc.targetPrefix}"
        "--enable-cross-compile"
        "--host-cc=${base.buildPackages.stdenv.cc}/bin/cc"
      ];
    });

  # The pinned release asset for this system, if any: the shared helper fetches, re-links and
  # scans it; a system without a pin gets the source build.
  prebuiltArtifact = import ./prebuilt-artifact.nix {
    inherit pkgs source;
    artifact = artifacts.ffmpeg.${system} or null;
    label = "ffmpeg";
    subject = "ffmpeg";
  };
in {
  inherit source enable;
  inherit (prebuiltArtifact) pinError;
  ffmpeg = prebuiltArtifact.result;
}
