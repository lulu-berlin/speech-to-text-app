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

  pin = artifacts.mesaVulkan.${system} or null;
  assetName = builtins.unsafeDiscardStringContext (baseNameOf pin.url);

  # A pin's paths are plain strings in nix/desktop-artifacts.nix, so nothing would make them inputs.
  # Nix records as references only the store paths that are inputs of a derivation, so without the
  # attached `path` context the drivers' RUNPATH libraries are dropped from the output's closure —
  # and from the bundle's payload — while the files still name them. With it, the build depends on
  # them and the closure is complete.
  referenced = map (p: builtins.appendContext p {"${p}" = {path = true;};}) pin.references;

  # A registered prebuilt Mesa: the release asset, fetched by a step of our own so that a missing
  # asset or one that does not match its pin fails naming the asset, its url and its sha256 (the
  # fixed-output hash Nix checks afterwards is the second gate). Then it is unpacked and re-linked:
  # the asset was built at another store path, and Mesa names that path in its three ICD manifests
  # and its three Vulkan drivers, so every occurrence of it becomes this output's own path (same
  # length). Each pinned direct reference is made an input, so the output's closure is complete, and
  # the build fails if any other store path survives. It never depends on `source`: a configured
  # artifact does not fall back to compiling.
  prebuilt = let
    name = assetName;
    tools = pkgs.buildPackages;
    fetch = tools.writeShellScript "fetch-${name}" ''
      export PATH=${lib.makeBinPath [tools.curl tools.coreutils]}
      export SSL_CERT_FILE=${tools.cacert}/etc/ssl/certs/ca-bundle.crt
      name=${lib.escapeShellArg name}
      url=${lib.escapeShellArg pin.url}
      sha256=${lib.escapeShellArg pin.sha256}
      fail() {
        echo "desktop artifact $name (mesaVulkan.${system} in nix/desktop-artifacts.nix): $1" >&2
        echo "  url:    $url" >&2
        echo "  sha256: $sha256" >&2
        exit 1
      }
      if ! error=$(curl --fail --silent --show-error --location --retry 3 --output "$out" "$url" 2>&1); then
        fail "missing: $error"
      fi
      got=$(sha256sum "$out" | cut -d' ' -f1)
      if [ "$got" != "$sha256" ]; then
        fail "does not match its pin: got sha256 $got"
      fi
    '';
    asset =
      derivation {
        inherit name;
        system = pkgs.stdenv.buildPlatform.system;
        builder = fetch;
        outputHashMode = "flat";
        outputHashAlgo = "sha256";
        outputHash = pin.sha256;
        impureEnvVars = lib.fetchers.proxyImpureEnvVars;
        preferLocalBuild = true;
      }
      // {
        inherit fetch;
        inherit (pin) url sha256;
      };
  in
    pkgs.runCommand source.name {
      nativeBuildInputs = [tools.zstd tools.perl tools.coreutils tools.gnugrep];
      # The source build's own no-LLVM rule. The path is a plain string so LLVM does not become a
      # build input (nixpkgs' `outputChecks` wrapper would do the same, but it applies only to
      # structured attrs).
      disallowedRequisites = [(builtins.unsafeDiscardStringContext (lib.getLib pkgs.mesa.passthru.llvmPackages.libllvm).outPath)];
      passthru = {inherit asset;};
    } ''
      mkdir $out
      tar -x --zstd -f ${asset} -C $out
      # The files that name the asset's build path — the three ICD manifests and the three drivers —
      # get this output's path instead; the scan below proves none was missed.
      old=${lib.escapeShellArg pin.storePath}
      files=$(grep -rlF -- "$old" $out || true)
      # `old` must reach perl's environment (a shell variable is not visible in %ENV); without it
      # the empty pattern rewrites every position and the manifests stop being JSON.
      if [ -n "$files" ]; then old="$old" out="$out" perl -pi -e 's/\Q$ENV{old}\E/$ENV{out}/g' $files; fi
      if [ -n "$files" ] && grep -qF -- "$old" $files; then
        echo "desktop artifact ${name}: these files still name ${pin.storePath}" >&2
        exit 1
      fi
      if ! stray=$(bash ${./store-path-scan.sh} $out ${lib.escapeShellArgs referenced}); then
        echo "desktop artifact ${name}: names store paths that are neither itself nor a pinned reference:" >&2
        echo "$stray" >&2
        exit 1
      fi
    '';

  # A registered asset is only valid for the source build it was made from: its files name that
  # build's libraries and its own store path. When this flake's source Mesa has another out path
  # (nixpkgs bumped, the override edited), evaluation fails here, before anything is fetched or
  # compiled. The text is exposed for the flake's check, which cannot read an evaluation error.
  sourcePath = builtins.unsafeDiscardStringContext source.outPath;
  pinError =
    if pin == null || pin.storePath == sourcePath
    then null
    else ''
      desktop artifact ${assetName} (mesaVulkan.${system} in nix/desktop-artifacts.nix) was made from another Mesa build than this flake's; rebuild the asset from this flake's Mesa and pin the new release.
        url:       ${builtins.unsafeDiscardStringContext pin.url}
        sha256:    ${pin.sha256}
        storePath: ${pin.storePath}
        this flake's Mesa: ${sourcePath}
    '';
in {
  inherit source pinError;
  mesa =
    if pin == null
    then source
    else if pinError != null
    then throw pinError
    else prebuilt;
}
