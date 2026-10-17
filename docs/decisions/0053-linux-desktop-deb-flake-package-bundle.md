# 0053 — The Linux desktop app ships as a `.deb`, a flake package and a `nix bundle`; the Nix store is a model cache

Status: accepted · Date: 2026-10-15

## Context

Roadmap item 50 packages the app as an Electron desktop application. Its first Linux artifact is
an electron-builder `.deb` (decided 2026-10-14: a root install keeps Chromium's setuid sandbox on
Ubuntu 23.10+, and the package's declared dependencies pull the system libraries). It bundles a
jlink JRE and the pinned prebuilt whisper-cli and bytedeco ffmpeg, and is verified by a packaged
e2e in a stock `ubuntu:24.04` container and on a plain CI runner.

Measured 2026-10-15 (`readelf -V` over the staged binaries), the `.deb`'s glibc floor is 2.38:
Electron needs 2.25, the JRE 2.15, ffmpeg 2.35, the whisper-cli prebuild and its
`libggml-base`/`libggml-vulkan` 2.38. So the `.deb` reaches Ubuntu 23.10+ and Debian 13+ through
apt, and nothing else. The user: a build only for Ubuntu/Debian, or a container of one, does not
meet the requirement of building for Linux.

The flake already builds everything the desktop app runs except Electron — the backend, the UI,
whisper-cpp 1.8.4 with Vulkan and `ffmpeg_8` (item 16, `nix run .`) — and a Nix closure carries
its own glibc. The models are `pkgs.fetchurl` fixed-output derivations (`nix/models.nix`), pinned
by sha256 in `versions.json`, and the desktop app downloads them on first start (item 50 test
item 3) to keep the artifact small.

## Decision

- **Three Linux artifacts, none exclusive:**
  - the **`.deb`**, unchanged, for apt-based systems with glibc 2.38+;
  - a **flake package**, `packages.x86_64-linux.desktop` (`nix run .#desktop`), for any
    distribution with Nix, NixOS included: the flake's backend, UI, whisper-cpp and `ffmpeg_8`, a
    JRE and Electron, laid out like the `.deb`'s resources so the app code is shared;
  - a **`nix bundle`** of that package: one self-contained executable for Linux machines that are
    neither Debian-based nor have Nix.
- **One pinned Electron, identical in every build:** the `.deb`'s upstream 44.7.1 is the version;
  the flake package takes the same release as a fixed-output derivation pinned next to the other
  downloads, not nixpkgs' `electron`, and a check fails when the two pins differ.
- **First-run model download in all three.** The flake package does not reference the models, so
  neither its closure nor the bundle made from it carries them (~600 MB).
- **The Nix store is a download cache.** A fixed-output derivation's store path is a function of
  its name and its content hash alone, so the app computes each pinned model's store path from the
  file name and sha256 in `versions.json` — no reference, no Nix at run time. If that file exists,
  it is used at once (the download screen shows it complete; store paths are content-addressed, so
  it is not re-hashed); if it does not, the normal download runs, and a miss is never an error.
  Verified 2026-10-15: the computed paths of the three pinned models equal the real store paths.
- **CI has three desktop jobs, one per artifact:** `desktop-e2e` builds the `.deb` and installs it
  on a plain runner (no Nix); `desktop-nix-e2e` builds `.#desktop` and runs the packaged specs
  against it in a stock Ubuntu container; `desktop-bundle-e2e` builds `.#desktop-bundle` and runs
  them in a stock Alpine container (no Nix, no glibc). The bundle's GPU specs need a GPU and run on
  a developer's machine. "Any distribution" rests on the Nix closure, not on a distribution matrix.
- No Flatpak (the user).

## Consequences

- Two build pipelines for one app: electron-builder with the jlink JRE and prebuilt tools for the
  `.deb`, the flake for the Nix package and the bundle. Their tools differ in origin (prebuilt vs
  nixpkgs builds of the same pinned versions); the shared packaged specs keep their behavior equal.
- A machine that has run `nix run .` or the devenv shell starts any of the three desktop artifacts
  without downloading the models. A garbage-collected store only means a download.
- Inside a bundle whose own `/nix/store` hides the host's, the cache never hits; the bundle
  downloads like the `.deb` without Nix.
- **The bundle's launcher is bubblewrap**, not one of the upstream bundlers: `nix/desktop-bundle.nix`
  packs the closure as a zstd tar and appends it to a shell launcher that unpacks once into
  `${XDG_CACHE_HOME:-~/.cache}/stt-app-bundle/<payload hash>`, then runs the app under
  `bwrap --unshare-user --unshare-pid --dev-bind /dev /dev --proc /proc --ro-bind <cache>/nix /nix`
  plus a `--bind` of every other top-level entry of `/` onto bubblewrap's scratch root: **the app
  sees the whole machine like any installed app, with the user's own permissions, except `/nix`**
  (the user, 2026-10-15, after first asking for a read-only machine and withdrawing it — saving a
  transcript wherever the Save dialog points must work). Binding `/` itself would make bubblewrap
  create `/nix` on the host's real root, which fails on a machine without Nix. Measured 2026-10-15
  (the user's runs, with the earlier narrower binds; the bundle e2e on Alpine covers these): the
  app starts with its window and **Chromium's own sandbox intact**, which is what neither
  `NixOS/bundlers#toArx` (its launcher cannot sandbox: the store cannot hold a setuid helper) nor
  its AppImage (`userns-chroot` AppRun) manages; both also need `bzip2`/FUSE and the former
  extracts into a chroot that broke fontconfig on a NixOS host.
  bubblewrap is started through the *extracted* store's dynamic loader (`ld-linux … --library-path
  … bwrap`), because a store binary's ELF interpreter is an absolute path that does not exist on a
  machine without Nix — the alternative would be shipping a static bwrap (verified 2026-10-15 in
  the sandbox: the loader invocation runs `bwrap --version`, and the self-extraction + exec of the
  appended payload works). Host requirements: `sh`, `tar`, `zstd`, unprivileged user namespaces; no
  root. The bundle is built with `nix build .#desktop-bundle` or
  `nix bundle --bundler .#desktop .#packages.x86_64-linux.desktop`.
- **The bundle brings its own fonts and ignores the host's** (the user, 2026-10-15): a generated
  fontconfig file (`FONTCONFIG_FILE`, set by the launcher) lists only IBM Plex (ADR 0033) — Plex
  Sans plus the Plex families for the scripts of `docs/api/languages.json`: Arabic, Devanagari,
  Hebrew, Thai, Chinese SC and TC, Japanese, Korean — in the three weights the UI uses
  (regular/medium/semibold), TTF only. Measured 2026-10-15: +32.9 MB to the bundle (zstd -19),
  +69.4 MB unpacked; the whole families would be +179 MB / +329 MB. Plex has no font for 15 of the
  list's scripts (Tamil, Malayalam, Telugu, Bengali/Assamese, Kannada, Armenian, Gurmukhi, Sinhala,
  Khmer, Georgian, Gujarati, Ethiopic, Lao, Myanmar, Tibetan) nor for Hausa's `ƙ ƴ Ɗ Ƙ Ƴ` and
  Lingala's capital `Ɛ Ɔ`: in the bundle, transcripts in those show missing glyphs, while the `.deb`
  and the Nix package keep using the host's fonts. Han characters take their glyph forms from the
  fallback order SC, TC, JP, because the UI does not mark a transcript's language (`lang`) — so
  Japanese kana and kanji render in the SC design.
- **The bundle brings GTK's icon themes too** (the user, 2026-10-15), like its fonts: the launcher
  points `XDG_DATA_DIRS` at them alone, so the file chooser looks the same on every host (with the
  earlier narrow binds there was no `/usr` and GTK drew placeholder icons). Adwaita (GTK's
  default) and hicolor (the fallback every theme inherits) are in the closure and on
  `XDG_DATA_DIRS`, which Electron's GTK wrapper prefixes with its settings schemas: +0.56 MB to the
  bundle (zstd -19), +13 MB unpacked (adwaita-icon-theme 50.0, measured 2026-10-15).
- **The bundle transcribes on the GPU through its own Vulkan drivers** (the user, 2026-10-15): a
  distribution's driver cannot load into the bundle's processes (nixpkgs' glibc ignores
  `/etc/ld.so.cache` and searches only its own lib dirs), so the bundle ships a Vulkan-only Mesa —
  RADV (AMD), ANV (Intel), NVK (NVIDIA on `nouveau`) — for whisper-cli, with no OpenGL drivers and
  so no LLVM (the build fails if LLVM reaches it); the window keeps rendering on the CPU. The
  launcher adds the drivers with `VK_ADD_DRIVER_FILES`; the kernel side is the host's `/dev/dri`.
  Measured 2026-10-15: closure 158.8 MiB (stock Mesa 1.07 GiB), bundle 388 → 401 MB; RADV verified
  in an Ubuntu container on the user's Radeon 680M, ANV and NVK unverified (no such hardware or
  driver setup). It is a nixpkgs override, so it compiles from source (~6 min; known issue). On
  machines running NVIDIA's own kernel driver, the host's NVIDIA Vulkan library — which must match
  that driver's version — is borrowed rather than shipped: when `/dev/nvidiactl` exists, the
  launcher links only NVIDIA's libraries (`libGLX_nvidia`, `libEGL_nvidia`, `libnvidia-*`) from
  the distribution's library directory into a private directory, puts it on `LD_LIBRARY_PATH`
  with the bundle's copies of what they link (`nix/nvidia-driver-libs.nix`), and adds the host's
  `nvidia_icd.json`; NVIDIA's Vulkan driver needs its EGL part, which the bundle's libglvnd finds
  through the host's `/usr/share/glvnd/egl_vendor.d`. Verified 2026-10-15 in an Ubuntu container
  with NVIDIA's 595.71.05 user-space libraries on the user's RTX 3050.
  On NixOS the bundled drivers need nothing from the host store, so AMD, Intel and `nouveau` should
  work there too (unverified); NVIDIA's own driver lives in the host store the bundle hides, so it
  is out of reach there — `nix run .#desktop` is the way on NixOS (the user: no need to bundle).
- Open at the time of this decision, settled in item 50's roadmap file: the bundle's measured size
  and where its e2e runs (the harness container), and how Chromium's sandbox behaves for
  store-installed Electron on Ubuntu 23.10+ (no setuid helper in the store; AppArmor restricts user
  namespaces — the bundle's own `unshare` is subject to the same restriction there, where the `.deb`
  is the answer).
- Pinning upstream Electron means the flake owns its Nix-side wrapping (library paths, GPU), which
  nixpkgs' `electron` would otherwise provide.
- Supersedes the drafted "desktop packaging stays outside the flake", which never reached `main`.
