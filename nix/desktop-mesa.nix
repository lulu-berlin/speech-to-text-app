# The desktop bundle's Vulkan-only Mesa (roadmap items 50 and 56): `source` is the Nix build of it,
# `mesa` the one the bundle ships for this package set's system — the prebuilt release asset when
# `artifacts` (nix/desktop-artifacts.nix) registers one for the system, otherwise `source`.
{
  pkgs,
  artifacts ? import ./desktop-artifacts.nix,
}: let
  lib = pkgs.lib;
  system = pkgs.stdenv.hostPlatform.system;

  # GPU transcription (the user, 2026-10-15): Mesa's Vulkan drivers only — RADV (AMD), ANV (Intel)
  # and NVK (NVIDIA on the nouveau kernel driver) — for whisper-cli, with no OpenGL drivers and so
  # no LLVM at run time; the window keeps rendering on the CPU. The kernel side is the host's
  # (/dev/dri); everything in user space is the bundle's, because a distribution's own driver
  # cannot load here (nixpkgs' glibc searches only its own lib dirs). Stock nixpkgs Mesa would
  # add a 1.07 GiB closure (LLVM alone 550 MiB); this variant is not in cache.nixos.org, so a
  # system without a prebuilt asset builds it from source. LLVM and clang stay build inputs —
  # ANV's shader kernels are compiled with mesa_clc during the build — but the output must not
  # reference LLVM: disallowedRequisites fails the build if it does.
  source =
    (pkgs.mesa.override {
      galliumDrivers = [];
      vulkanDrivers = ["amd" "intel" "nouveau"];
      vulkanLayers = [];
    }).overrideAttrs (old: let
      # OpenGL-side features that need Gallium drivers, replaced by the flags below, and the
      # Apple/Mali developer tools (`tools`), whose drivers are not built.
      dropped = ["glvnd" "gbm" "libgbm-external" "teflon" "gallium-rusticl" "gallium-rusticl-enable-drivers" "gallium-extra-hud" "tools"];
      kept = flag: !(lib.any (name: lib.hasPrefix "-D${name}=" flag) dropped);
    in {
      pname = "stt-bundle-mesa-vulkan";
      # nixpkgs' Meson hook forces every optional feature on (`-Dauto_features=enabled`), which
      # fails for the Gallium-only ones (first build: "Feature gallium-va cannot be disabled: VA
      # state tracker requires at least one of the following gallium drivers"). `auto` builds an
      # optional feature when its prerequisites are there; the flags below stay explicit.
      mesonAutoFeatures = "auto";
      mesonFlags =
        lib.filter kept old.mesonFlags
        ++ [
          (lib.mesonEnable "glvnd" false)
          (lib.mesonEnable "gbm" false)
          (lib.mesonBool "teflon" false)
          (lib.mesonBool "gallium-rusticl" false)
          (lib.mesonBool "opengl" false)
          (lib.mesonEnable "gles1" false)
          (lib.mesonEnable "gles2" false)
          (lib.mesonEnable "egl" false)
          (lib.mesonEnable "glx" false)
          # RADV compiles with its own ACO; its optional LLVM backend is off.
          (lib.mesonBool "amd-use-llvm" false)
        ];
      # Only the outputs this build fills: the stock `opencl` (Rusticl) and `spirv2dxil` (Dozen)
      # hold OpenGL-side pieces it does not build, and Nix fails on a declared output that was
      # never created (second build: "failed to produce output path for output 'spirv2dxil'").
      # `cross_tools` keeps the build-time compilers, so they stay out of the bundle's closure.
      outputs = ["out" "cross_tools"];
      postInstall = ''
        for tool in asahi_clc intel_clc mesa_clc panfrost_compile panfrost_texfeatures panfrostdump pco_clc vtn_bindgen2; do
          moveToOutput bin/$tool $cross_tools
        done
      '';
      # The stock fixup rewrites EGL and Vulkan-layer manifests and patches libgallium, none of
      # which this build has.
      postFixup = ''
        find $out -type d -empty -delete
      '';
      # Only `out` ships in the bundle; `cross_tools`' mesa_clc is built with clang and LLVM.
      outputChecks.out.disallowedRequisites = [(lib.getLib old.passthru.llvmPackages.libllvm)];
    });

  # The pin for this system, if any. The helper owns the fixed-output fetch (which fails naming the
  # asset, its url and sha256), the unpack-and-re-link, the stray-path scan, the reference pinning
  # and the evaluation-time stale-pin gate; a system without a pin gets the source build.
  prebuiltArtifact = import ./prebuilt-artifact.nix {
    inherit pkgs source;
    artifact = artifacts.mesaVulkan.${system} or null;
    label = "mesaVulkan";
    subject = "Mesa";
    # The source build's own no-LLVM rule: `out` ships in the bundle, `cross_tools`' mesa_clc is
    # built with clang and LLVM and does not. The path is a plain string so LLVM does not become a
    # build input.
    extraAttrs.disallowedRequisites = [(builtins.unsafeDiscardStringContext (lib.getLib pkgs.mesa.passthru.llvmPackages.libllvm).outPath)];
  };
in {
  inherit source;
  inherit (prebuiltArtifact) pinError;
  mesa = prebuiltArtifact.result;
}
