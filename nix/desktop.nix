# The desktop app as a flake package (roadmap item 50, ADR 0053): `nix build .#desktop`,
# `nix run .#desktop`. The same app code as the .deb (desktop/src, compiled by tsc) on the flake's
# own parts: the backend jars, the built UI, whisper-cpp 1.8.4 (Vulkan) and ffmpeg 8.1 — the ones
# `nix run .` ships — a jlink JRE, and the pinned upstream Electron. The resources are laid out as
# electron-builder lays them out for the .deb, so desktop/src/backend.ts finds everything where
# it looks. The models are NOT referenced: the app downloads them on first start or takes them
# from the Nix store at their fixed-output paths (desktop/src/models.ts), so neither this closure
# nor a `nix bundle` of it carries ~600 MB of models.
# Nix evaluation happens on the user's machine - this file is unverified here.
{
  pkgs,
  nixpkgs,
  sourceOf,
  backend,
  frontend,
  whisperCpp,
  ffmpeg,
}: let
  electronPin = import ./electron.nix;
  packageJson = builtins.fromJSON (builtins.readFile ../desktop/package.json);

  # The Java runtime, as the .deb builds it (desktop/stage.ts): jlink over exactly the modules the
  # backend needs, from the list both packages read. nixpkgs' `jre` is the full JDK (572 MiB in the
  # first measurement, 2026-10-15).
  jre = pkgs.jre_minimal.override {modules = jlinkModules;};
  jlinkModules = builtins.filter (line: line != "" && builtins.substring 0 1 line != "#") (
    pkgs.lib.splitString "\n" (builtins.readFile ../desktop/jlink-modules.txt)
  );

  # nixpkgs' own recipe for a prebuilt Electron (the locked rev's
  # pkgs/development/tools/electron/binary/generic.nix: `version: hashes:`), called with our pin
  # instead of nixpkgs' version: it sets the interpreter and an rpath over Electron's libraries
  # (GTK, NSS, ALSA, X11/xcb, libgbm, libGL, the Vulkan loader, ...) and wraps the binary with the
  # GTK environment. `headers` is only for native node modules, which this app has none of; the
  # attribute is never built.
  #
  # Its whole library set stays. Switching four of them off (speech synthesis, screen capture,
  # GTK 4, printing) was measured on 2026-10-15 and **saved nothing**: the closure's drop from
  # 2152.9 to 1678.5 MiB was the JRE alone (571.8 -> 97.9), python3/flite/freepats/gtk4 stayed in
  # the closure through other references, and dropping `cups` broke Electron outright — its binary
  # has libcups.so.2 as a hard DT_NEEDED (`error while loading shared libraries: libcups.so.2`,
  # exit 127, all six specs). Cutting the rest needs the referrers of each package, not guesses.
  #
  # One argument is replaced instead of removed: the recipe takes `systemd` for libudev, and
  # nixpkgs' libudev-only package is `udev` (`systemdLibs`, the same one `pkgs.udev` points at on
  # Linux). The full systemd was 62.8 MiB of the closure.
  electron =
    if packageJson.devDependencies.electron != electronPin.version
    then throw "nix/electron.nix pins Electron ${electronPin.version} but desktop/package.json pins ${packageJson.devDependencies.electron}: one Electron for every build (ADR 0053)"
    else
      (pkgs.callPackage "${nixpkgs}/pkgs/development/tools/electron/binary/generic.nix" {
        systemd = pkgs.udev;
        # pipewire (screen capture) and gtk4 (the GTK 4 window mode): the app uses neither, and
        # with the two SDL entry points cut (nix/whisper-cpp.nix, the ffmpeg override below) they
        # are the last paths to the remaining GStreamer cluster —
        # `nix why-depends --all` on 2026-10-15 shows electron -> gtk4 -> gst-plugins-bad ->
        # gstreamer/gst-plugins-base/libnice/openal-soft/flite -> python3, and electron -> pipewire.
        # Cutting them alone changed nothing earlier: back then `sdl2-compat -> sdl3 -> zenity ->
        # libadwaita` reached gtk4 as well. Order matters; this is measured again after the cut.
        pipewire = null;
        gtk4 = null;
      })
      electronPin.version {
        inherit (electronPin) x86_64-linux;
        headers = pkgs.lib.fakeSha256;
      };

  # The app's own files, as the .deb's app.asar holds them: build/ (tsc output of desktop/src),
  # progress.html, package.json. `pnpm build` is `tsc` over the two app projects.
  app = pkgs.stdenv.mkDerivation (finalAttrs: {
    pname = "stt-desktop-app";
    inherit (packageJson) version;
    src = sourceOf [
      ../desktop/src
      ../desktop/package.json
      ../desktop/pnpm-lock.yaml
      ../desktop/pnpm-workspace.yaml
      ../desktop/tsconfig.json
      ../desktop/tsconfig.renderer.json
      ../desktop/progress.html
    ];
    sourceRoot = "source/desktop";

    nativeBuildInputs = [
      pkgs.nodejs
      pkgs.pnpm_11
      pkgs.pnpmConfigHook
      pkgs.pnpmBuildHook
    ];
    # As for the frontend: serial installs, no lifecycle scripts (electron's postinstall
    # download is skipped by design, desktop/pnpm-workspace.yaml), capped network concurrency.
    pnpmInstallFlags = [
      "--child-concurrency=1"
      "--network-concurrency=2"
      "--ignore-scripts"
    ];
    pnpmDeps = pkgs.fetchPnpmDeps {
      inherit (finalAttrs) pname version src sourceRoot pnpmBuildScript pnpmInstallFlags;
      pnpm = pkgs.pnpm_11;
      fetcherVersion = 4;
      # pnpm store hash of desktop/pnpm-lock.yaml, generated by the fixed-output build
      # (fetcherVersion = 4); a lockfile change makes the build report the new one.
      hash = "sha256-clTy2KrCzJmp8VBIzmBut9FSA4EQ+lFKAcDWTuquPuc=";
    };
    pnpmBuildScript = "build";

    installPhase = ''
      runHook preInstall
      mkdir -p $out
      cp -r build progress.html package.json $out/
      runHook postInstall
    '';
  });

  # The tools the backend runs by name (ADR 0047), where backend.ts puts them first on PATH.
  # `ffmpeg` is the flake's audio-only prebuilt artifact (roadmap item 62), the same binary the
  # server package and the devenv shell ship; the backend runs its CLI only: decode to mono f32
  # PCM, `silencedetect`/`atrim`, FLAC out, and the V10 concat backfill.
  tools = pkgs.runCommand "stt-desktop-tools" {} ''
    mkdir -p $out/bin
    ln -s ${whisperCpp}/bin/whisper-cli $out/bin/whisper-cli
    ln -s ${ffmpeg}/bin/ffmpeg $out/bin/ffmpeg
  '';
in
  pkgs.stdenv.mkDerivation {
    pname = "stt-desktop";
    inherit (packageJson) version;
    src = pkgs.writeTextDir "empty-src" "";
    nativeBuildInputs = [pkgs.makeWrapper];
    dontConfigure = true;
    dontBuild = true;
    installPhase = ''
      runHook preInstall
      resources=$out/share/stt-app/resources
      mkdir -p $out/bin $resources/backend $resources/tools
      # The app directory is copied, not linked: main.ts takes the resources directory as the
      # parent of app.getAppPath(), which a symlink would resolve into the app's own store path.
      cp -r ${app} $resources/app
      ln -s ${jre} $resources/jre
      ln -s ${backend}/lib $resources/backend/lib
      ln -s ${frontend} $resources/ui
      ln -s ${tools}/bin $resources/tools/bin
      cp ${../versions.json} $resources/versions.json
      # setsid and kill come from the package too (ADR 0047), like the server wrapper's PATH.
      makeWrapper ${electron}/bin/electron $out/bin/stt-app \
        --add-flags $resources/app \
        --prefix PATH : ${pkgs.lib.makeBinPath [pkgs.util-linux pkgs.coreutils]}
      runHook postInstall
    '';
    meta = {
      description = "Speech-to-Text desktop app (Electron)";
      mainProgram = "stt-app";
    };
    # Roadmap item 62: the audio-only ffmpeg this app's tools carry, so the flake's
    # `ffmpeg-audio-only-everywhere` check can assert the choice at evaluation, without building the
    # app.
    passthru.ffmpeg = ffmpeg;
  }
