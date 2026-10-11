# The app as a Nix flake package (roadmap item 16; item 02 started it, ADR 0014 as
# amended by ADR 0016). `nix build .` builds the backend and the frontend and provides
# bin/stt-app (bin/speech-to-text-app before item 16): a java wrapper that defaults
# STT_DATA_DIR to the XDG data home's stt-app directory (ADR 0017 amended), defaults
# both models from nix/models.nix (a user-set value wins), and puts the pinned
# whisper-cli 1.8.4 (Vulkan), ffmpeg 8.1, setsid and coreutils first on PATH (ADR 0047).
# Nix evaluation happens on the user's machine - this file is unverified here.
{
  description = "Speech-to-Text app (backend + bundled frontend, roadmap item 16)";

  inputs = {
    # Shared-with-devenv pin question is roadmap item 16's open question (ADR 0014).
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = {
    self,
    nixpkgs,
    ...
  }: let
    system = "x86_64-linux";
    pkgs = nixpkgs.legacyPackages.${system};
    models = import ./nix/models.nix {inherit pkgs;};

    # whisper-cli pinned to the 1.8.4 tag (item 33 consultation, 2026-10-11): the Gradle
    # bootstrap downloads the jiang1997 1.8.4 prebuild, so both paths run one version.
    # Vulkan is the GPU path on any Linux host with a Vulkan driver. nixpkgs' recipe is
    # for 1.9.2, and two of its parts differ at 1.8.4: the install check also runs
    # parakeet-cli, which 1.8.4 does not have, and its WHISPER_COMMON_FFMPEG option is
    # still named WHISPER_FFMPEG there, so the flag is a no-op and whisper-cli is built
    # without ffmpeg libs (it reads the backend's FLAC chunks through its bundled
    # miniaudio). The source hash is the NAR hash of the v1.8.4 tarball.
    whisperCpp = (pkgs.whisper-cpp.override {vulkanSupport = true;}).overrideAttrs {
      version = "1.8.4";
      src = pkgs.fetchFromGitHub {
        owner = "ggml-org";
        repo = "whisper.cpp";
        tag = "v1.8.4";
        hash = "sha256-YCuWKDKlrhbx+t3t2kDpAwuKt4rkipDsOXO6uqIU/W0=";
      };
      installCheckPhase = ''
        runHook preInstallCheck
        "$out/bin/whisper-cli" --help >/dev/null
        runHook postInstallCheck
      '';
    };

    backend = pkgs.stdenv.mkDerivation (finalAttrs: {
      pname = "stt-backend";
      version = "0.1.0-SNAPSHOT";
      src = self;

      # Directory sources unpack to `./source/` (stripHash of the store path);
      # nixpkgs cds into `$sourceRoot` after unpackPhase, so it must include the
      # `source/` prefix. Gradle then runs in backend/.
      sourceRoot = "source/backend";

      # The unpacked copy keeps the store's 0555 directory modes; nixpkgs only
      # chmods $sourceRoot, so the update script's exit-trap `rm -rf` would fail
      # (EPERM noise) on the sibling dirs. Make the whole copy writable.
      postUnpack = "chmod -R u+w .";

      nativeBuildInputs = [
        pkgs.gradle
        pkgs.makeWrapper
        pkgs.jre
      ];

      # Rewritten by nixpkgs' gradle.fetchDeps mitm proxy.
      # GENERATION (user): `nix build .#backend.mitmCache.updateScript --print-out-paths`
      #   then run the printed script; it writes backend/deps.json (see gradle.section.md).
      # Downstream (non-nixpkgs) packages must pass `pkg` explicitly; pname alone is
      # resolved as an attribute path inside nixpkgs and fails. The default bwrapFlags
      # bind-mounts $PWD read-only (fine for nixpkgs, broken for us): make it writable
      # so the unpack phase can prepare the source and write deps.json.
      mitmCache = pkgs.gradle.fetchDeps {
        pkg = finalAttrs.finalPackage;
        data = ./backend/deps.json;
        bwrapFlags = ''--bind "$PWD" "$PWD"'';
      };

      # In-process Kotlin compilation (ADR 0022) and Gradle's default JVM memory
      # settings, -Xmx512m -XX:MaxMetaspaceSize=384m (ADR 0025: the former
      # 384m/256m cap ran out of metaspace in :compileKotlin).
      gradleFlags = ["-Pkotlin.compiler.execution.strategy=in-process"];

      # The Nix check runs `gradle check` (ktlintCheck + tests, ADR 0015): nixpkgs'
      # gradle setup hook defaults gradleCheckTask to `test` (verified in the pinned
      # nixpkgs setup-hook.sh), so it must be overridden. installDist produces bin/ +
      # all runtime jars (the plain jar has no dependencies on its own).
      gradleBuildTask = "installDist";
      gradleCheckTask = "check";
      doCheck = true;

      installPhase = ''
        runHook preInstall
        mkdir -p $out/lib
        # Distribution lib/: the app jar + all runtime dependencies (the plain
        # jar has none). The generated launch scripts are not installed - they
        # need /bin/sh, which is not guaranteed at runtime.
        cp -r build/install/stt-backend/lib/. $out/lib/
        runHook postInstall
      '';
    });

    frontend = pkgs.stdenv.mkDerivation (finalAttrs: {
      pname = "stt-frontend";
      version = "0.1.0";
      src = self;

      sourceRoot = "source/frontend";

      # pnpm 11 for the committed pnpm-lock.yaml (lockfileVersion 9, ADR 0012).
      nativeBuildInputs = [
        pkgs.nodejs
        pkgs.pnpm_11
        pkgs.pnpmConfigHook
        pkgs.pnpmBuildHook
      ];

      # Symlink-nothing; pnpmConfigHook + pnpmBuildHook need the store.
      # The sandbox OOM-killed pnpm's default install parallelism; keep it serial,
      # skip lifecycle scripts (none of our deps need them - rolldown's binding is an
      # optional dependency, not a postinstall) and cap network concurrency.
      pnpmInstallFlags = [
        "--child-concurrency=1"
        "--network-concurrency=2"
        "--ignore-scripts"
      ];

      pnpmDeps = pkgs.fetchPnpmDeps {
        inherit (finalAttrs) pname version src sourceRoot pnpmBuildScript pnpmInstallFlags;
        pnpm = pkgs.pnpm_11;
        fetcherVersion = 4;
        # pnpm store hash, generated by the fixed-output build (fetcherVersion = 4).
        hash = "sha256-Z1wLj7+IopzeTIHNMjTquGSKUSwkcZgrPjNTO9p91vM=";
      };

      # `pnpm build` runs tsc --noEmit && vite build (ADR 0015 still applies).
      pnpmBuildScript = "build";

      installPhase = ''
        runHook preInstall
        mkdir -p $out
        # The build ran in source/frontend (sourceRoot), so dist is in $PWD.
        cp -r dist/. $out/
        runHook postInstall
      '';
    });
  in {
    packages.x86_64-linux = {
      inherit backend frontend;

      # `nix build .` builds both parts and provides bin/stt-app, which
      # starts the backend serving the bundled frontend. A real derivation (not
      # symlinkJoin) so the wrapper can hard-code the store paths via makeWrapper.
      default = pkgs.stdenv.mkDerivation {
        pname = "stt-app";
        version = "0.1.0";
        src = pkgs.writeTextDir "empty-src" "";
        nativeBuildInputs = [backend frontend pkgs.makeWrapper pkgs.jre];
        dontConfigure = true;
        dontBuild = true;
        installPhase = ''
          runHook preInstall
          mkdir -p $out/bin $out/lib $out/share/stt-app/static
          # The frontend lives in its own directory ($out/share/stt-app/static): the app
          # serves it over HTTP, so the backend jars and the wrapper must NOT sit inside
          # the static root (review fix: /lib/*.jar and /bin/* were previously exposed).
          cp -rL ${frontend}/. $out/share/stt-app/static/
          cp -rL ${backend}/lib/. $out/lib/
          CP="$(printf '%s:' "$out"/lib/*.jar)"
          # --set, not --set-default: the -default form silently keeps a
          # set-but-empty variable (devenv exports an empty CLASSPATH),
          # and the packaged app always needs its own jars and frontend.
          # --run: STT_DATA_DIR unset or empty defaults to the XDG data home's stt-app
          # directory (roadmap item 16, amending ADR 0017 for the packaged app), which the
          # wrapper creates. The doubled quotes escape the dollar signs below, so the
          # wrapper (not Nix) expands the variables.
          # --set-default for the models: the pinned whisper model (ADR 0008) and CAM++
          # model (ADR 0003) from nix/models.nix unless the user sets one (item 21's e2e
          # suite swaps in the tiny whisper model).
          # --prefix PATH: the backend runs whisper-cli, ffmpeg and setsid by name (ADR 0047);
          # the package's own tools come first, so a caller's PATH (e.g. the devenv's
          # whisper-cli) cannot replace them. ffmpeg_8, not nixpkgs' default ffmpeg (9.0 at
          # the locked rev): item 33's Gradle bootstrap syncs ffmpeg to the 8.1 branch.
          makeWrapper ${pkgs.jre}/bin/java $out/bin/stt-app \
            --run 'if [ -z "''${STT_DATA_DIR:-}" ]; then export STT_DATA_DIR="''${XDG_DATA_HOME:-$HOME/.local/share}/stt-app"; fi; ${pkgs.coreutils}/bin/mkdir -p "$STT_DATA_DIR"' \
            --set STT_STATIC_DIR "$out/share/stt-app/static" \
            --set-default STT_WHISPER_MODEL ${models.whisper} \
            --set-default STT_SPEAKER_MODEL ${models.speaker} \
            --prefix PATH : ${pkgs.lib.makeBinPath [whisperCpp pkgs.ffmpeg_8 pkgs.util-linux pkgs.coreutils]} \
            --set CLASSPATH "$CP" \
            --add-flags "stt.MainKt"
          runHook postInstall
        '';
        meta.description = "Speech-to-Text app (backend + bundled frontend)";
      };
    };
  };
}
