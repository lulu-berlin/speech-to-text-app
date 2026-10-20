# Prebuilt artifacts of the desktop bundle and the app (roadmap items 56, 62 and 63): GitHub Release
# assets of this repository, uploaded by the user, each pinned by url and sha256. Keyed by artifact,
# then by Nix system (`pkgs.stdenv.hostPlatform.system`); a system without an entry builds the
# artifact from source. Pure data, read by nix/desktop-mesa.nix, nix/ffmpeg.nix and
# nix/whisper-cpp.nix.
{
  # The bundle's Vulkan-only Mesa, per system: { url; sha256; storePath; references; } — storePath
  # is the out path of the source build the asset was made from, references that output's direct
  # references (`nix-store -q --references`, itself excluded).
  mesaVulkan.x86_64-linux = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-mesa-v1/stt-bundle-mesa-vulkan-26.2.4-x86_64-linux.tar.zst";
    sha256 = "37f340e0840c0fc7beb62639587a0b475c218450091ae349e87ed0fc650830bc";
    storePath = "/nix/store/r68qzz1ndp2d7bbhchjb28ixkbakrq3y-stt-bundle-mesa-vulkan-26.2.4";
    references = [
      "/nix/store/0yvm79dfl53xg4vi2mg01cljfpnxkcnc-libdrm-2.4.134"
      "/nix/store/cvr8yyrrqnjxgd7bs6lwgra4qma7kwl9-libxcb-1.17.0"
      "/nix/store/gvhdvn542cwd3wr2978nh4ydzp89msq7-systemd-minimal-libs-261.3"
      "/nix/store/h4wfwic161kxrr74jlzla5lsm28hgary-glibc-2.44-25"
      "/nix/store/j7qx4s4mr17j1wqgvqdzj33lmrnzb387-gcc-16.2.0-lib"
      "/nix/store/ja1if6cmxwmlhs64w6fxy965sg9n7d8k-libxcb-keysyms-0.4.1"
      "/nix/store/li1008zymvj7sz8jkdk9brkslfq9jy8w-wayland-1.26.0"
      "/nix/store/mc33sic07k7an0knwd73sq5xygg2rigg-libdisplay-info-0.4.0"
      "/nix/store/ph4ib8mxy5yrw40k7a1l03bfd58hq6wj-zstd-1.5.7"
      "/nix/store/s7306mp3pdbw8xx2gicsqvyganjhsywp-elfutils-0.195"
      "/nix/store/vv038kwd5p5mlqd5ycbnvr1zmvmvkic4-libxshmfence-1.3.3"
      "/nix/store/znrm90algr0fvnnnp130am1qfg59nl1f-libx11-1.8.13"
      "/nix/store/zrkqwk0989k5wa1gn3cjb76jfga3d8i3-expat-2.8.5"
    ];
  };

  # The audio-only static ffmpeg (roadmap item 62): one binary, no ffprobe. Its only store-path
  # reference is its own build path (the `configuration:` line), which the re-link rewrites, so the
  # references list is empty.
  ffmpeg.x86_64-linux = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-ffmpeg-v2/ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2-x86_64-linux.tar.zst";
    sha256 = "945db2902395cfc3a5c002de35fbddc4b3ea14de76ee50d5b3bbfbce98fc184c";
    storePath = "/nix/store/2pqr6g1ll2dzj7p87cz5a6w8amv2pp22-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2";
    references = [];
  };

  # The pinned whisper-cpp (roadmap item 63): the 1.8.4 Vulkan build (no SDL, no nixpkgs ffmpeg)
  # whisper-cli ships. Its files name the libraries below and its own build path, which the re-link
  # rewrites.
  whisperCpp.x86_64-linux = {
    url = "https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-whisper-cpp-v1/whisper-cpp-1.8.4-x86_64-linux.tar.zst";
    sha256 = "33329560d092342ebdf61437413410f6e1f4ab769f6b91164f1f06038a79f1cf";
    storePath = "/nix/store/n2vf0kiskxmm9mfrs5mcla5q1k7zqpmi-whisper-cpp-1.8.4";
    references = [
      "/nix/store/10dxp0qxqxxsyiljrh2kp0xqhz6arhcx-bash-5.3p15"
      "/nix/store/h4wfwic161kxrr74jlzla5lsm28hgary-glibc-2.44-25"
      "/nix/store/j7qx4s4mr17j1wqgvqdzj33lmrnzb387-gcc-16.2.0-lib"
      "/nix/store/l60xx259knfbgfr7wzwsw7zg9ajmv3sn-wget-1.25.0"
      "/nix/store/vjdqsn0bz2llabfqa746zfgk4kmswba5-vulkan-loader-1.4.357.0"
    ];
  };
}
