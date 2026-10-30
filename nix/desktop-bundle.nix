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

  # The bundle's fonts and their fontconfig configuration (nix/bundle-fonts.nix; roadmap item 74's
  # coverage check judges the same set).
  bundleFonts = import ./bundle-fonts.nix {inherit pkgs;};

  # The Linux desktop identity shared with the Nix package (roadmap item 74): .desktop entry and
  # icon, which the launcher writes out only when asked.
  identity = import ./desktop-identity.nix {inherit lib;};

  # GTK's icon themes (the user, 2026-10-15), the bundle's own like its fonts: the launcher points
  # XDG_DATA_DIRS at them alone, so the file chooser looks the same on every host (when the
  # namespace had no /usr, it found no theme at all and drew placeholders). Adwaita is GTK's default
  # theme, hicolor the fallback every theme inherits: +0.56 MB to the bundle (zstd -19), +13 MB
  # unpacked (adwaita-icon-theme 50.0, measured 2026-10-15). The launcher puts them on
  # XDG_DATA_DIRS; Electron's GTK wrapper (wrapGAppsHook3) prefixes its settings schemas to that.
  iconThemes = [pkgs.adwaita-icon-theme pkgs.hicolor-icon-theme];

  # GPU transcription: the Vulkan-only Mesa (nix/desktop-mesa.nix).
  vulkanMesa = (import ./desktop-mesa.nix {inherit pkgs;}).mesa;

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
        $(cat ${pkgs.closureInfo {rootPaths = [desktop bwrap bundleFonts.fontsConf vulkanMesa] ++ iconThemes ++ nvidiaDriverLibs;}}/store-paths) \
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

    # The menu entry and icon, written or removed only when asked (the user, 2026-10-29): the bundle
    # has no install step and installs nothing by itself. Both travel inside this script, so the
    # flags need no unpacking; the entry launches this file where it lies. Removal deletes exactly
    # the two files installation writes, and nothing else in the data directory.
    case "''${1-}" in
      --install-desktop-entry)
        case $self in /*) ;; *) self=$PWD/$self ;; esac
        data=''${XDG_DATA_HOME:-$HOME/.local/share}
        mkdir -p "$data/applications" "$data/icons/hicolor/scalable/apps"
        # The Exec value as the desktop-entry spec defines it: double-quoted, with backslashes,
        # double quotes, backticks and dollars escaped, so a bundle under a path with spaces or
        # those characters still launches from the menu.
        quoted=$(printf '%s' "$self" | sed -e 's/[\\"]/\\&/g' -e 's/[`$]/\\&/g')
        cat > "$data/applications/${identity.desktopName}" <<ENTRY
    ${identity.entry "\"$quoted\""}
    ENTRY
        cat > "$data/icons/hicolor/scalable/apps/stt-app.svg" <<'ICON'
    ${lib.removeSuffix "\n" (builtins.readFile identity.icon)}
    ICON
        echo "stt-app-bundle: installed $data/applications/${identity.desktopName} and its icon" >&2
        exit 0
        ;;
      --remove-desktop-entry)
        data=''${XDG_DATA_HOME:-$HOME/.local/share}
        rm -f "$data/applications/${identity.desktopName}" "$data/icons/hicolor/scalable/apps/stt-app.svg"
        echo "stt-app-bundle: removed $data/applications/${identity.desktopName} and its icon" >&2
        exit 0
        ;;
    esac

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
    libs=@libs@
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
      --setenv FONTCONFIG_FILE ${bundleFonts.fontsConf} \
      --setenv XDG_DATA_DIRS ${lib.makeSearchPath "share" iconThemes} \
      --setenv VK_ADD_DRIVER_FILES "$drivers" \
      "$@"
  '';
in
  pkgs.runCommand "stt-app-bundle" {
    nativeBuildInputs = [pkgs.bash pkgs.coreutils pkgs.gnused];
    passthru = {inherit bwrap payload vulkanMesa nvidiaDriverLibs;} // {inherit (bundleFonts) fonts fontsConf;};
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
    # The launcher's library path is read here, at build time: `lib.fileContents` would force this
    # build during evaluation, which makes `nix flake check` fail on a cold store (2026-10-19).
    libs=$(sed 's|/nix/store/|$store/|g' ${bwrapLibs})
    libs=''${libs%:}
    sed -i \
      -e "s|@libs@|$libs|" \
      -e "s|@id@|$(sha256sum ${payload} | cut -c1-16)|" \
      -e "s|@payload_bytes@|$(stat -c%s ${payload})|" \
      $out/bin/stt-app-bundle
    cat ${payload} >> $out/bin/stt-app-bundle
  ''
