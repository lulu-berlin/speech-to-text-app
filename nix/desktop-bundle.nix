# The desktop app as one self-contained executable (ADR 0053, roadmap item 50): a zstd-compressed
# tar of the package's closure, followed by a launcher that unpacks it once and runs it in a
# bubblewrap namespace. What it is for: a Linux machine with neither apt/Debian nor Nix, where
# neither the `.deb` nor `nix run .#desktop` applies.
#
# The launcher starts bubblewrap through the *extracted* store's dynamic loader: a store binary's
# ELF interpreter is an absolute /nix/store path that does not exist on a machine without Nix, and
# calling the loader directly (with an explicit library path) avoids shipping a static binary. This
# was verified on 2026-10-15 (`ld-linux-x86-64.so.2 --library-path … bwrap --version`), and the
# namespace itself — the app with its window and Chromium's own sandbox intact, the extracted store
# bound at /nix — was verified on the user's machine, and the bundle e2e (Alpine) runs it.
#
# Host requirements (documented in the README): `sh`, `tar`, `zstd` and unprivileged user
# namespaces. Nothing else is installed, and no root is needed — not even fonts or icon themes: the
# bundle brings its own and ignores the host's, so it looks the same on every machine.
{
  pkgs,
  desktop,
}: let
  lib = pkgs.lib;

  bwrap = pkgs.bubblewrap;

  # The launcher's library path: every `lib/` directory of bubblewrap's own closure, so a single
  # `--library-path` covers bubblewrap and its dependencies.
  bwrapLibs =
    pkgs.runCommand "stt-bundle-bwrap-libs" {
      nativeBuildInputs = [pkgs.bash pkgs.coreutils];
    } ''
      for path in $(cat ${pkgs.closureInfo {rootPaths = [bwrap];}}/store-paths); do
        if [ -d "$path/lib" ]; then printf '%s/lib:' "$path"; fi
      done > $out
    '';

  # The fonts: IBM Plex Sans (the app's own typeface, ADR 0033) and the Plex families that cover the
  # scripts of the languages in docs/api/languages.json — Arabic (ar, fa, ur, ps, sd), Devanagari
  # (hi, mr, ne, sa), Hebrew (he, yi), Thai, Chinese SC and TC (zh, yue; SC lacks common Traditional
  # characters such as 見 and 門), Japanese and Korean. Only the three weights the UI uses
  # (regular, medium, semibold) and only TTF: 27 files, +32.9 MB to the bundle (zstd -19) and
  # +69.4 MB unpacked, against +179 MB / +329 MB for the whole families (measured 2026-10-15).
  # Plex has no font for the other scripts in that list (Tamil, Malayalam, Telugu, Bengali/Assamese,
  # Kannada, Armenian, Gurmukhi, Sinhala, Khmer, Georgian, Gujarati, Ethiopic, Lao, Myanmar,
  # Tibetan) nor for Hausa's ƙ ƴ Ɗ Ƙ Ƴ and Lingala's capital Ɛ Ɔ; the bundle shows those as missing
  # glyphs (ADR 0053).
  plex = pkgs.ibm-plex.override {
    families = ["sans" "sans-arabic" "sans-devanagari" "sans-hebrew" "sans-thai" "sans-sc" "sans-tc" "sans-jp" "sans-kr"];
  };
  fonts = pkgs.runCommand "stt-bundle-fonts" {} ''
    mkdir -p $out/share/fonts/truetype
    for weight in Regular Medium SemiBold; do
      cp ${plex}/share/fonts/truetype/*-$weight.ttf $out/share/fonts/truetype/
    done
    count=$(ls $out/share/fonts/truetype | wc -l)
    if [ "$count" -ne 27 ]; then
      echo "expected 27 font files (9 families x 3 weights), got $count" >&2
      exit 1
    fi
  '';

  # The bundle's whole font configuration: only the fonts above, no host directories and no host
  # `conf.d` (the namespace sees the host's /etc). The fallback order decides Han glyph forms, since
  # the UI does not mark a transcript's language: Simplified Chinese first, then Traditional, then
  # Japanese.
  fontFamilies = [
    "IBM Plex Sans"
    "IBM Plex Sans Arabic"
    "IBM Plex Sans Devanagari"
    "IBM Plex Sans Hebrew"
    "IBM Plex Sans Thai"
    "IBM Plex Sans SC"
    "IBM Plex Sans TC"
    "IBM Plex Sans JP"
    "IBM Plex Sans KR"
  ];
  fontAlias = generic: ''
    <alias binding="same">
      <family>${generic}</family>
      <prefer>
        ${lib.concatMapStrings (family: "<family>${family}</family>") fontFamilies}
      </prefer>
    </alias>
  '';
  fontsConf = pkgs.writeText "stt-bundle-fonts.conf" ''
    <?xml version="1.0"?>
    <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
    <fontconfig>
      <dir>${fonts}/share/fonts</dir>
      <cachedir prefix="xdg">fontconfig</cachedir>
      ${lib.concatMapStrings fontAlias ["sans-serif" "sans" "system-ui"]}
    </fontconfig>
  '';

  # GTK's icon themes (the user, 2026-10-15), the bundle's own like its fonts: the launcher points
  # XDG_DATA_DIRS at them alone, so the file chooser looks the same on every host (when the
  # namespace had no /usr, it found no theme at all and drew placeholders). Adwaita is GTK's default
  # theme, hicolor the fallback every theme inherits: +0.56 MB to the bundle (zstd -19), +13 MB
  # unpacked (adwaita-icon-theme 50.0, measured 2026-10-15). The launcher puts them on
  # XDG_DATA_DIRS; Electron's GTK wrapper (wrapGAppsHook3) prefixes its settings schemas to that.
  iconThemes = [pkgs.adwaita-icon-theme pkgs.hicolor-icon-theme];

  # GPU transcription (the user, 2026-10-15): Mesa's Vulkan drivers only — RADV (AMD), ANV (Intel)
  # and NVK (NVIDIA on the nouveau kernel driver) — for whisper-cli, with no OpenGL drivers and so
  # no LLVM at run time; the window keeps rendering on the CPU. The kernel side is the host's
  # (/dev/dri); everything in user space is the bundle's, because a distribution's own driver
  # cannot load here (nixpkgs' glibc searches only its own lib dirs). Stock nixpkgs Mesa would
  # add a 1.07 GiB closure (LLVM alone 550 MiB); this variant is not in cache.nixos.org, so it
  # builds from source (docs/known-issues.md). LLVM and clang stay build inputs — ANV's shader
  # kernels are compiled with mesa_clc during the build — but the output must not reference
  # LLVM: disallowedRequisites fails the build if it does.
  vulkanMesa =
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

  # The libraries NVIDIA's own driver links, from the bundle: on a host running NVIDIA's kernel
  # driver the launcher borrows the host's matching user-space driver and puts these beside it.
  nvidiaDriverLibs = import ./nvidia-driver-libs.nix pkgs;

  # The payload: the whole closure (the app, its JRE, the tools, the Electron it ships, bubblewrap,
  # the fonts, the icon themes, the Vulkan drivers and NVIDIA's driver libraries) as one
  # zstd-compressed tar. The models are deliberately *not* here — the app downloads them on first
  # run or takes them from a store (ADR 0053) — so the bundle stays ~500 MB smaller than it would be.
  payload =
    pkgs.runCommand "stt-desktop-bundle-payload" {
      nativeBuildInputs = [pkgs.zstd];
    } ''
      tar -cf - \
        --hard-dereference \
        --sort=name \
        --mtime="@$SOURCE_DATE_EPOCH" \
        --owner=0 --group=0 --mode=u+rw,uga+r \
        $(cat ${pkgs.closureInfo {rootPaths = [desktop bwrap fontsConf vulkanMesa] ++ iconThemes ++ nvidiaDriverLibs;}}/store-paths) \
        | zstd -19 -T0 -o $out
    '';

  # Where a store path lands inside the extracted tree, e.g. /nix/store/x-y -> $store/x-y.
  inBundle = path: "$store/" + lib.removePrefix "/nix/store/" path;

  # POSIX sh only (ADR 0056): this is the one script of ours that runs where Nix is absent, and the
  # launcher's final `exec` must stay the last statement — everything after it is the compressed
  # payload, which the shell would otherwise try to read as more script. Verified 2026-10-15 under
  # dash: the launcher unpacks its own tail and reaches the exec.
  launcher = pkgs.writeShellScript "stt-app-bundle-launcher" ''
    # The desktop app, unpacking itself on first run (see the derivation that built this file).
    set -eu

    self=$0
    id=@id@
    payload_bytes=@payload_bytes@
    cache=''${XDG_CACHE_HOME:-$HOME/.cache}/stt-app-bundle/$id

    if [ ! -e "$cache/.extracted" ]; then
      for tool in tar zstd; do
        command -v "$tool" >/dev/null 2>&1 || {
          echo "stt-app-bundle: $tool is required to unpack the app (install it, for example" >&2
          echo "  'sudo apt install $tool' or 'sudo dnf install $tool')" >&2
          exit 1
        }
      done
      echo "stt-app-bundle: unpacking into $cache (once)" >&2
      mkdir -p "$cache"
      tail -c "$payload_bytes" "$self" | tar -x --zstd -C "$cache"
      : > "$cache/.extracted"
    fi

    store=$cache/nix/store
    loader=${inBundle "${lib.getLib pkgs.glibc}/lib/ld-linux-x86-64.so.2"}
    libs=${lib.replaceStrings ["/nix/store/"] ["$store/"] (lib.removeSuffix ":" (lib.fileContents bwrapLibs))}
    bwrap=${inBundle "${bwrap}/bin/bwrap"}
    app=${inBundle "${desktop}/bin/stt-app"}

    # NVIDIA's own driver, borrowed (ADR 0053): its user-space part must match the host's kernel
    # module, so the bundle cannot ship it. Only when that kernel driver runs (/dev/nvidiactl) and
    # the driver's Vulkan manifest and libGLX_nvidia sit where distributions install them: a private
    # directory links NVIDIA's libraries — and nothing else of the host, whose other libraries would
    # clash with the bundle's — and goes on the library path ahead of the libraries they link from
    # the bundle; the host's manifest (naming libGLX_nvidia.so.0) joins the bundle's drivers.
    # NVIDIA's Vulkan driver declines to start without its EGL part (vkCreateInstance stays NULL),
    # so libEGL_nvidia is linked too: the bundle's libglvnd finds it through the host's vendor
    # manifest in /usr/share/glvnd/egl_vendor.d, a directory it searches by default.
    drivers=${vulkanMesa}/share/vulkan/icd.d
    libraries=""
    if [ -e /dev/nvidiactl ]; then
      manifest=""
      glx=""
      for dir in /etc/vulkan/icd.d /usr/share/vulkan/icd.d /usr/local/share/vulkan/icd.d; do
        if [ -z "$manifest" ] && [ -f "$dir/nvidia_icd.json" ]; then manifest=$dir/nvidia_icd.json; fi
      done
      for dir in /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib; do
        if [ -z "$glx" ] && [ -e "$dir/libGLX_nvidia.so.0" ]; then glx=$dir; fi
      done
      if [ -n "$manifest" ] && [ -n "$glx" ]; then
        nvidia=$cache/nvidia
        mkdir -p "$nvidia"
        for library in "$glx"/libGLX_nvidia.so* "$glx"/libEGL_nvidia.so* "$glx"/libnvidia-*.so*; do
          if [ -e "$library" ]; then ln -sf "$library" "$nvidia/"; fi
        done
        drivers=$drivers:$manifest
        libraries=$nvidia:${lib.makeLibraryPath nvidiaDriverLibs}
      fi
    fi

    # The app sees the whole machine like any installed app, with the user's own permissions (the
    # user, 2026-10-15): a recording, a model or a Save-dialog folder anywhere, the GPU's device
    # nodes and drivers, the runtime directory with its sockets. Only /nix is the bundle's own,
    # read-only store, which hides a host's /nix. Each top-level entry of / is bound onto
    # bubblewrap's scratch root rather than / itself: bubblewrap creates a missing mount point, and
    # on a host without Nix it could not create /nix on the host's real root. /dev is bound with
    # devices allowed (a plain bind is nodev) and /proc is fresh for the PID namespace. Fonts and
    # icon themes stay the bundle's: FONTCONFIG_FILE and XDG_DATA_DIRS point at them. The Vulkan
    # loader gets the bundle's GPU drivers through VK_ADD_DRIVER_FILES (additive: a driver it finds
    # on its own still loads). The binds are collected in the positional parameters, so no path is
    # ever word-split.
    set -- --chdir "$HOME" "$app" "$@"
    for entry in /*; do
      case $entry in /dev | /nix | /proc) continue ;; esac
      if [ -e "$entry" ]; then set -- --bind "$entry" "$entry" "$@"; fi
    done
    if [ -n "$libraries" ]; then set -- --setenv LD_LIBRARY_PATH "$libraries" "$@"; fi
    exec "$loader" --library-path "$libs" "$bwrap" \
      --unshare-user --unshare-pid --die-with-parent \
      --dev-bind /dev /dev \
      --proc /proc \
      --ro-bind "$cache/nix" /nix \
      --setenv FONTCONFIG_FILE ${fontsConf} \
      --setenv XDG_DATA_DIRS ${lib.makeSearchPath "share" iconThemes} \
      --setenv VK_ADD_DRIVER_FILES "$drivers" \
      "$@"
  '';
in
  pkgs.runCommand "stt-app-bundle" {
    nativeBuildInputs = [pkgs.bash pkgs.coreutils pkgs.gnused];
    passthru = {inherit bwrap payload fonts fontsConf vulkanMesa nvidiaDriverLibs;};
    meta = {
      description = "Speech-to-Text desktop app as one self-contained executable";
      mainProgram = "stt-app-bundle";
    };
  } ''
    mkdir -p $out/bin
    # Writable (the store copy is 0555), because the payload is appended below.
    install -m 755 ${launcher} $out/bin/stt-app-bundle
    # This launcher is the one artefact of ours that runs on a machine without Nix, so its shebang
    # must not be a store path (ADR 0056): `writeShellScript` bakes the store's bash there, and the
    # script is written in POSIX sh for the host's own shell to find. Everything else Nix builds —
    # the package's wrapped `bin/stt-app`, the flake's apps — keeps the pinned store interpreter.
    sed -i '1c #!/usr/bin/env sh' $out/bin/stt-app-bundle

    # Bake the payload's size and identity into the launcher, then append the payload: the launcher
    # reads its own tail, so the two must be written in this order.
    sed -i \
      -e "s|@id@|$(sha256sum ${payload} | cut -c1-16)|" \
      -e "s|@payload_bytes@|$(stat -c%s ${payload})|" \
      $out/bin/stt-app-bundle
    cat ${payload} >> $out/bin/stt-app-bundle
  ''
