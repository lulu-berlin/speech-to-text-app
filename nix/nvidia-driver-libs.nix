# The libraries NVIDIA's own user-space driver links that a host's driver install does not bring
# into a Nix-built process (roadmap items 19 and 50): libGLX_nvidia needs libX11 and more, and a
# Nix process finds them only on its library path — nixpkgs' glibc ignores the host's ld.so.cache,
# and a store-installed driver's RUNPATH names host store paths. Mirrors nixpkgs' nvidia-x11 libPath.
pkgs: [
  pkgs.libdrm
  pkgs.libxext
  pkgs.libx11
  pkgs.libxv
  pkgs.libxrandr
  pkgs.libxcb
  pkgs.zlib
  pkgs.stdenv.cc.cc
  pkgs.wayland
  pkgs.libgbm
  pkgs.libGL
  pkgs.openssl
  pkgs.dbus
]
