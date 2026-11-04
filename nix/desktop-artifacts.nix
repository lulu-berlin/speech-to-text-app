# Prebuilt artifacts of the desktop bundle and the app (roadmap items 56, 62, 63 and 48): GitHub
# Release assets of this repository, uploaded by the user, each pinned by url and sha256. Keyed by
# artifact, then by Nix system (`pkgs.stdenv.hostPlatform.system`); a system without an entry builds
# the artifact from source. Pure data, read by nix/desktop-mesa.nix, nix/ffmpeg.nix and
# nix/whisper-cpp.nix. A static asset that names no store path (the darwin whisper-cli) records
# `storePath = null` and no references: there is no build path to rewrite and no stale-pin to catch.
{
  # The bundle's Vulkan-only Mesa, per system: { url; sha256; storePath; references; } — storePath
  # is the out path of the source build the asset was made from, references that output's direct
  # references (`nix-store -q --references`, itself excluded).
  mesaVulkan.x86_64-linux = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/mesa-26.2.6/stt-bundle-mesa-vulkan-26.2.6-x86_64-linux.tar.zst";
    sha256 = "0b43fa20c75648fa2954d7799f5f6e55b8901219d13fc4e9d4991e457ada7576";
    storePath = "/nix/store/j0pdncfbs3x9f6kv4hpb24i15c6dlgms-stt-bundle-mesa-vulkan-26.2.6";
    references = [
      "/nix/store/05qiq1wpq0v63fhfsj851jkib1zjdsc1-libdisplay-info-0.4.0"
      "/nix/store/7a2cjhcgzyym5zaz6m4fksw47qvm150w-gcc-16.2.0-lib"
      "/nix/store/7rx5im6jbgyfhrl0lc0kyxd9yf14rdxc-expat-2.9.0"
      "/nix/store/8vz293r6dqfci9p6m955yziz2agxx990-wayland-1.26.0"
      "/nix/store/bvfkqam1y3zmsb3k41iml0zwx13sknjl-elfutils-0.195"
      "/nix/store/cgzr5afjdbx30j7g44z2h92i8h3828k2-systemd-minimal-libs-262"
      "/nix/store/d5dsf0h0r0fpi48rgvi7hmnac3mzj2s8-libxcb-1.17.0"
      "/nix/store/d6ygipmny4pw7h61b255yafbnvqjbmnx-libx11-1.8.14"
      "/nix/store/f5bymphw4ci3j34w3iw63v32pqpkxkjf-libdrm-2.4.134"
      "/nix/store/f5r77q40xh7rj30dfkajv8y6jwbf4xh2-zstd-1.5.7"
      "/nix/store/hkqzh1vx6kr4mis703ph6gmnl3790x9k-libxcb-keysyms-0.4.1"
      "/nix/store/pqyiidz5mi73wv5f91d48s9plcqyj5bz-glibc-2.44-57"
      "/nix/store/v4j317f11g4px2cc4ad7cldqkn0wdbqz-libxshmfence-1.3.3"
    ];
  };

  # The audio-only static ffmpeg (roadmap item 62): one binary, no ffprobe. Its only store-path
  # reference is its own build path (the `configuration:` line), which the re-link rewrites, so the
  # references list is empty.
  ffmpeg.x86_64-linux = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/ffmpeg-8.1.3/ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.3-x86_64-linux.tar.zst";
    sha256 = "9c6ffc0611e9d28afd0fa5089cb788850530e5ce82d350a45babe34c872ab734";
    storePath = "/nix/store/vnhygj96qdxbvrr05f1a3fy5267ydbnd-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.3";
    references = [];
  };

  # The same build for Apple Silicon (roadmap item 69), made by the manual `macos-ffmpeg-asset`
  # job: one binary whose only dependencies are the OS's libraries, no ffprobe. The asset is a
  # `.tar.gz` (a stock macOS host has no zstd), and it is the one the flake, `./gradlew
  # prepareRuntime` and the DMG ship. Its build path is the only store path in the binary, which
  # the re-link rewrites, so the references list is empty.
  ffmpeg.aarch64-darwin = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/ffmpeg-8.1.3/ffmpeg-audio-only-8.1.3-aarch64-darwin.tar.gz";
    sha256 = "d65ac745ec9a083bd478b999a4276b07717a4f225762aad3cc3ecb0376780390";
    storePath = "/nix/store/w5flp0s2b2lymyybdi08145qjpvlnrl1-ffmpeg-audio-only-8.1.3";
    references = [];
  };

  # The pinned whisper-cpp (roadmap item 63, 1.9.5 by roadmap item 90): the Vulkan build (no SDL, no
  # nixpkgs ffmpeg) whisper-cli ships, published under the version-named tag whisper-cpp-1.9.5 as
  # one `.tar.gz` per system. Its files name the libraries below and its own build path, which the
  # re-link rewrites.
  whisperCpp.x86_64-linux = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/whisper-cpp-1.9.5/whisper-cpp-1.9.5-x86_64-linux.tar.gz";
    sha256 = "9bfb1aec1eacff88c55298c9c8585f754fb044a554f8f6daf12e0caa8ecbbd68";
    storePath = "/nix/store/mf301gvwz6pva5cfnmv5r973wh026zla-whisper-cpp-1.9.5";
    references = [
      "/nix/store/7a2cjhcgzyym5zaz6m4fksw47qvm150w-gcc-16.2.0-lib"
      "/nix/store/ajixkliv0pkhzlzc7m8p0z8rmxnyfvyc-wget-1.25.0"
      "/nix/store/nnm9gdr70ihc6zc9298mzz8jpcgaciql-bash-5.3p20"
      "/nix/store/nxk3gwrrd83pvdl1qvwfgs1ir5cn64mz-llama-cpp-0.8.0"
      "/nix/store/pqyiidz5mi73wv5f91d48s9plcqyj5bz-glibc-2.44-57"
      "/nix/store/ss8mlnazqliy7vrr3iga26kbnlrxs7bb-llama-cpp-0.8.0-dev"
    ];
  };

  # Apple Silicon (roadmap item 48's follow-up): the static Metal/BLAS 1.9.5 build the manual
  # `macos-whisper-asset` CI job compiles and the Gradle bootstrap downloads (nix/versions.nix
  # aarch64-darwin) — one binary, system frameworks only, no store paths. The Nix package and the
  # devenv run it too, so all three paths ship the same tool.
  whisperCpp.aarch64-darwin = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/whisper-cpp-1.9.5/whisper-cpp-1.9.5-aarch64-darwin.tar.gz";
    sha256 = "35c620d2430f4ca766e5771a5101dc4cc9b0785dbe3e1a73c889c2795fba162a";
    storePath = null;
    references = [];
  };
}
