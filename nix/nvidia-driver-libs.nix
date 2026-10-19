# The libraries NVIDIA's own user-space driver links that a host's driver install does not bring
# into a Nix-built process (roadmap items 19 and 50): libGLX_nvidia needs libX11 and more, and a
# Nix process finds them only on its library path — nixpkgs' glibc ignores the host's ld.so.cache,
# and a store-installed driver's RUNPATH names host store paths. Mirrors nixpkgs' nvidia-x11 libPath.
#
# Only the entries' lib outputs are listed. The list is also a root of the bundle payload's closure
# (nix/desktop-bundle.nix), which realizes each root's default output: the full packages would pack
# their build closures too — the full gcc and isl/libmpc/linux-headers/glibc-dev for libstdc++ —
# while the driver only needs the libraries (roadmap item 62 found the ~400 MB).
pkgs:
  map (p: pkgs.lib.getLib p) [
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
