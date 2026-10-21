# The app's audio-only ffmpeg (roadmap item 62): one static binary built for exactly the
# backend's ffmpeg surface, so `nix/desktop-artifacts.nix` can pin a release asset of it and the
# desktop, the server package, Docker and the devenv ship the same bytes without compiling ffmpeg.
#
# `source` is the static build the asset is made from. Its name is the final, re-linked output's name
# too (`source.name`; pkgsStatic adds `-static-<host>`: `ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2`).
# The asset is built at another store path, which the binary's own `configuration:` line names, and
# the re-link rewrites it in place — same-length paths, so both derivations use this exact name.
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
  # 48). The pinned release asset exists only for x86_64-linux; a system without a pin builds
  # from source.
  base = if pkgs.stdenv.hostPlatform.isLinux then pkgs.pkgsStatic else pkgs;
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;
  # ffmpeg's configure target: Linux keeps the pinned asset's exact flags; darwin needs its own
  # target, or configure cross-compiles for linux/x86_64 with the wrong compiler (roadmap item 48).
  targetOS = if pkgs.stdenv.hostPlatform.isDarwin then "darwin" else "linux";
  targetArch = if pkgs.stdenv.hostPlatform.isAarch64 then "aarch64" else "x86_64";

  # The component lists the build's `--enable-<kind>=…` flags come from, and the same lists the
  # `ffmpeg-audio-only` app compares the built configuration against: one source, so the check cannot
  # drift from the recipe.
  enable = [
    {
      kind = "demuxer";
      components = ["aac" "aiff" "amr" "asf" "concat" "flac" "loas" "matroska" "mov" "mp3" "ogg" "wav"];
    }
    {
      kind = "decoder";
      components = [
        "aac"
        "aac_latm"
        "ac3"
        "alac"
        "amrnb"
        "amrwb"
        "adpcm_ima_qt"
        "adpcm_ima_wav"
        "adpcm_ms"
        "atrac3"
        "atrac3al"
        "atrac3plus"
        "atrac3plusal"
        "dca"
        "eac3"
        "flac"
        "g722"
        "mlp"
        "mp1"
        "mp2"
        "mp3"
        "mp3float"
        "opus"
        "pcm_alaw"
        "pcm_f32be"
        "pcm_f32le"
        "pcm_f64be"
        "pcm_f64le"
        "pcm_mulaw"
        "pcm_s16be"
        "pcm_s16be_planar"
        "pcm_s16le"
        "pcm_s16le_planar"
        "pcm_s24be"
        "pcm_s24le"
        "pcm_s24le_planar"
        "pcm_s32be"
        "pcm_s32le"
        "pcm_s32le_planar"
        "pcm_s64be"
        "pcm_s64le"
        "pcm_s8"
        "pcm_s8_planar"
        "pcm_u16be"
        "pcm_u16le"
        "pcm_u24be"
        "pcm_u24le"
        "pcm_u32be"
        "pcm_u32le"
        "pcm_u8"
        "speex"
        "truehd"
        "vorbis"
        "wmalossless"
        "wmapro"
        "wmav1"
        "wmav2"
        "wmavoice"
      ];
    }
    {
      kind = "parser";
      components = ["aac" "ac3" "flac" "mpegaudio" "opus" "vorbis"];
    }
    {
      kind = "encoder";
      components = ["flac" "pcm_f32le" "pcm_s16le"];
    }
    {
      kind = "muxer";
      components = ["flac" "pcm_f32le" "null"];
    }
    {
      kind = "filter";
      components = ["aformat" "anull" "anullsink" "aresample" "atrim" "silencedetect"];
    }
    {
      kind = "protocol";
      components = ["file" "pipe"];
    }
  ];
  linuxSource =
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
      ++ lib.optionals (base.stdenv.hostPlatform != base.stdenv.buildPlatform) [
        "--cross-prefix=${base.stdenv.cc.targetPrefix}"
        "--enable-cross-compile"
        "--host-cc=${base.buildPackages.stdenv.cc}/bin/cc"
      ];
    });

  # On darwin the app uses nixpkgs' cached ffmpeg_8-headless: the audio-only static recipe is
  # Linux-shaped (static libc) and its darwin configure is not maintained (roadmap item 48).
  source =
    if pkgs.stdenv.hostPlatform.isDarwin
    then pkgs.ffmpeg_8-headless
    else linuxSource;
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
