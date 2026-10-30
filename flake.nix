# The app as a Nix flake package (roadmap item 16; item 02 started it, ADR 0014 as
# amended by ADR 0016). `nix build .` builds the backend and the frontend and provides
# bin/stt-app (bin/speech-to-text-app before item 16): a java wrapper that defaults
# STT_DATA_DIR to the XDG data home's stt-app directory (ADR 0017 amended), defaults
# both models from nix/models.nix (a user-set value wins), and puts the pinned
# whisper-cli 1.8.4 (Vulkan), the audio-only ffmpeg 8.1, setsid and coreutils first on PATH (ADR 0047).
# `docker-root` is the Docker image's root filesystem (roadmap item 19, see Dockerfile).
# Nix evaluation happens on the user's machine - this file is unverified here.
#
# Roadmap item 33: the committed versions.json (the Gradle bootstrap's manifest) is
# the render of the `versions` attrset below; apps.update-versions regenerates it and
# checks.versions-sync fails `nix flake check` on drift.
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
    # The systems the flake declares: the per-system outputs are built for each (roadmap
    # item 48 adds aarch64-darwin). Every one of them pins a prebuilt whisper-cpp artifact
    # (the policy check checks), so adding a platform cannot land without its asset.
    systems = ["x86_64-linux" "aarch64-darwin"];
    mkSystem = system: let
      pkgs = nixpkgs.legacyPackages.${system};
      isLinux = pkgs.stdenv.hostPlatform.isLinux;
      # The packaged app's per-user data directory (ADR 0017 as amended): XDG on Linux,
      # ~/Library/Application Support on macOS (roadmap item 48).
      dataDirDefault =
        if isLinux
        then ''"''${XDG_DATA_HOME:-$HOME/.local/share}/stt-app"''
        else ''"$HOME/Library/Application Support/stt-app"'';
      models = import ./nix/models.nix {inherit pkgs;};

      # The app's whisper-cpp (roadmap item 63): the pinned 1.8.4 Vulkan build shipped as the
      # prebuilt release asset on x86_64-linux (nix/whisper-cpp.nix); a system without a pin
      # gets the source build.
      whisperCppArtifact = import ./nix/whisper-cpp.nix {inherit pkgs;};
      whisperCpp = whisperCppArtifact.whisperCpp;

      # The app's ffmpeg (roadmap item 62): the audio-only build, shipped as the pinned
      # prebuilt artifact on x86_64-linux (nix/ffmpeg.nix) and built from the same recipe on
      # darwin (roadmap item 48); the version manifest reads the source build's version
      # (8.1.2, rendered as 8.1).
      ffmpegArtifact = import ./nix/ffmpeg.nix {inherit pkgs;};
      ffmpeg = ffmpegArtifact.ffmpeg;

      # The release tag of the audio-only ffmpeg asset. The enable list changes the source build's out
      # path, which invalidates the pinned asset, so a changed build gets a new tag (v2 after the
      # audio-decoder rework; v1 is the first build).
      ffmpegAssetTag = "desktop-ffmpeg-v2";

      # The release tag of the whisper-cpp asset. A changed recipe or nixpkgs bump changes the source
      # build's out path, which invalidates the pinned asset, so a changed build gets a new tag (v1 is
      # the first build).
      whisperCppAssetTag = "desktop-whisper-cpp-v1";

      # The release assets' producer checks (roadmap items 62, 63 and 69): each builds the source its
      # asset is made from. They are apps the user runs when publishing, not `checks` entries, so
      # `nix flake check` compiles neither source. The ffmpeg one exists on both systems (darwin's
      # asset is a `.tar.gz`); the whisper-cpp one stays Linux-only.
      assetPinChecks =
        {
          ffmpeg-asset-pin = import ./nix/asset-pin-check.nix {
            inherit pkgs;
            source = (import ./nix/ffmpeg.nix {inherit pkgs;}).source;
            asset = self.packages.${system}.desktop-ffmpeg-asset;
            pin = (import ./nix/desktop-artifacts.nix).ffmpeg.${system} or null;
            name = "ffmpeg-asset-pin";
            tag = ffmpegAssetTag;
            key = "ffmpeg";
          };
        }
        // pkgs.lib.optionalAttrs isLinux {
          whisper-cpp-asset-pin = import ./nix/asset-pin-check.nix {
            inherit pkgs;
            source = (import ./nix/whisper-cpp.nix {inherit pkgs;}).source;
            asset = self.packages.${system}.desktop-whisper-cpp-asset;
            pin = (import ./nix/desktop-artifacts.nix).whisperCpp.${system};
            name = "whisper-cpp-asset-pin";
            tag = whisperCppAssetTag;
            key = "whisperCpp";
          };
        };

      # The audio-only build's configure surface (roadmap items 62 and 69, test-list item 2 / item
      # 1): one binary, no ffprobe or ffplay, and a configure line that names exactly the app's
      # ffmpeg surface. On Linux the binary is static (readelf: no NEEDED, no INTERP); on darwin it
      # is a Mach-O that links libSystem dynamically, so `otool -L` must name only the OS's own
      # libraries (roadmap item 69).
      # It builds the source, so it is an app the user runs (`nix run .#ffmpeg-audio-only`), not a
      # `checks` entry — `nix flake check` then compiles no ffmpeg source.
      # The app-surface proofs (roadmap item 62), run against whichever binary the caller names: each
      # accepted extension decodes to the canonical FLAC, and the app's own ffmpeg commands work.
      ffmpegUploadFormats = ffmpeg: ''
        bash ${./nix/ffmpeg-upload-formats.sh} ${ffmpeg}/bin/ffmpeg ${./backend/src/test/resources/upload-formats} ${pkgs.lib.escapeShellArgs (builtins.fromJSON (builtins.readFile ./backend/src/main/resources/allowed-extensions.json))}'';
      ffmpegAppUses = ffmpeg: ''
        bash ${./nix/ffmpeg-app-uses.sh} ${ffmpeg}/bin/ffmpeg ${./backend/src/test/resources/upload-formats}'';

      ffmpegAudioOnlyCheck = let
        lib = pkgs.lib;
        isDarwin = pkgs.stdenv.hostPlatform.isDarwin;
        config = import ./nix/ffmpeg.nix {inherit pkgs;};
        audioOnly = config.source;
        ffmpeg = "${audioOnly}/bin/ffmpeg";
        # The same data the build derives its flags from (roadmap item 68 moved it to
        # nix/ffmpeg-enable.json so the Windows job builds from the same lists); the plain flags and
        # the tokens it must not carry, and the component sets; nix/ffmpeg-audio-only.sh compares the
        # built configuration to them exactly.
        surface = builtins.fromJSON (builtins.readFile ./nix/ffmpeg-enable.json);
        expected = pkgs.writeText "ffmpeg-audio-only-expected" ''
          ${lib.concatMapStrings (flag: "flag ${flag}\n") surface.flags}
          ${lib.concatMapStrings (forbid: "forbid ${forbid}\n") surface.forbid}
          ${lib.concatMapStrings ({
            kind,
            components,
          }: "${kind} ${lib.concatStringsSep "," components}\n")
          surface.enable}
        '';
      in
        pkgs.runCommand "ffmpeg-audio-only" {
          nativeBuildInputs =
            lib.optionals (!isDarwin) [pkgs.binutils]
            ++ [pkgs.bash pkgs.coreutils pkgs.gnugrep pkgs.gnused]
            ++ lib.optionals isDarwin [pkgs.darwin.binutils-unwrapped];
        } ''
          fail() {
            echo "ffmpeg-audio-only: $1" >&2
            exit 1
          }
          [ -x ${ffmpeg} ] || fail "${ffmpeg} is missing"
          [ "$(ls ${audioOnly}/bin)" = ffmpeg ] || fail "bin/ holds more than ffmpeg: $(ls ${audioOnly}/bin)"
          [ ! -e ${audioOnly}/bin/ffprobe ] || fail "the build ships bin/ffprobe"
          [ ! -e ${audioOnly}/bin/ffplay ] || fail "the build ships bin/ffplay"
          ${lib.optionalString isDarwin ''
            # Darwin has no static libc: the av* code is linked into the binary, and the only
            # libraries it loads are the OS's own. `otool -L` prints the file's line, then one
            # dependency per line; the first field is the install path.
            deps=$(otool -L ${ffmpeg} | sed -n '2,$s/^[[:space:]]*\([^[:space:]]*\).*/\1/p')
            [ -n "$deps" ] || fail "otool -L lists no library dependency"
            grep -qxF /usr/lib/libSystem.B.dylib <<<"$deps" ||
              fail "otool -L does not name /usr/lib/libSystem.B.dylib"
            offenders=$(grep -vE '^(/usr/lib/|/System/Library/)' <<<"$deps" || true)
            [ -z "$offenders" ] || fail "the binary loads a non-system library: $(echo $offenders)"
          ''}
          ${lib.optionalString (!isDarwin) ''
            # Static: no library dependency and no program interpreter.
            if ${pkgs.binutils}/bin/readelf -d ${ffmpeg} 2>/dev/null | ${pkgs.gnugrep}/bin/grep -q NEEDED; then
              fail "the binary has dynamic library dependencies"
            fi
            if ${pkgs.binutils}/bin/readelf -l ${ffmpeg} 2>/dev/null | ${pkgs.gnugrep}/bin/grep -q INTERP; then
              fail "the binary has a program interpreter"
            fi
          ''}
          bash ${./nix/ffmpeg-audio-only.sh} ${ffmpeg} ${expected}
          echo ok > $out
        '';

      # Gradle 9.8 (nixpkgs' gradle_9) on the runtime JDK 21 (pkgs.jdk = pkgs.jre's JDK):
      # the same Gradle as the devenv and the committed wrapper (item 33, ADR 0049). 9.x
      # is the first line that runs on Java 25 (8.14's embedded Kotlin cannot parse it);
      # JDK 21 instead of gradle_9's default JDK 25 keeps the build and test JVM equal to
      # the app's runtime and to item 16's verified environment.
      gradle = pkgs.gradle_9.override {java = pkgs.jdk;};

      # Version manifest for the human Gradle bootstrap (roadmap item 33): the same
      # versions the flake builds with, rendered into the committed versions.json by the
      # update-versions app below. Node/pnpm/gradle/jdk/ffmpeg come from this nixpkgs
      # rev (ADR 0036), whisper-cpp and the prebuilt tool downloads from
      # nix/versions.nix, the models from nix/models.nix and the e2e test fixtures from
      # nix/test-fixtures.nix (whose derivations are built from the same tables). The
      # versions-sync check fails when versions.json drifts.
      versions = let
        data = import ./nix/versions.nix;
      in {
        nixpkgsRev = nixpkgs.rev;
        node = pkgs.nodejs.version;
        pnpm = pkgs.pnpm_11.version;
        gradle = gradle.version;
        jdk = pkgs.jre.version;
        ffmpeg = pkgs.lib.versions.majorMinor ffmpegArtifact.source.version;
        whisperCpp = data.whisperCppTag;
        models = models.meta;
        downloads = data.downloads;
        fixtures = (import ./nix/test-fixtures.nix {inherit pkgs;}).meta;
      };
      versionsJson = pkgs.writeText "versions.json" (builtins.toJSON versions);

      # Each part's source holds only what its build reads: its own directory, docs/api (the
      # frontend imports the contract JSON, the backend's contract tests read it) and, for
      # ktlint, the root .editorconfig. A commit elsewhere (roadmap, docs, e2e, devenv) leaves
      # both derivations unchanged, so it costs no rebuild. The paths are inside the flake's
      # store copy, which holds git-tracked files only. toSource names the result `source`.
      sourceOf = paths:
        pkgs.lib.fileset.toSource {
          root = ./.;
          fileset = pkgs.lib.fileset.unions paths;
        };

      backend = pkgs.stdenv.mkDerivation (finalAttrs: {
        pname = "stt-backend";
        version = "0.1.0-SNAPSHOT";
        src = sourceOf [./backend ./docs/api ./.editorconfig];

        # Directory sources unpack to `./source/` (stripHash of the store path);
        # nixpkgs cds into `$sourceRoot` after unpackPhase, so it must include the
        # `source/` prefix. Gradle then runs in backend/.
        sourceRoot = "source/backend";

        # The unpacked copy keeps the store's 0555 directory modes; nixpkgs only
        # chmods $sourceRoot, so the update script's exit-trap `rm -rf` would fail
        # (EPERM noise) on the sibling dirs. Make the whole copy writable.
        postUnpack = "chmod -R u+w .";

        nativeBuildInputs = [
          gradle
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
        mitmCache = gradle.fetchDeps {
          pkg = finalAttrs.finalPackage;
          data = ./backend/deps.json;
          bwrapFlags = ''--bind "$PWD" "$PWD"'';
        };

        # In-process Kotlin compilation (ADR 0022) and Gradle's default JVM memory
        # settings, -Xmx512m -XX:MaxMetaspaceSize=384m (ADR 0025: the former
        # 384m/256m cap ran out of metaspace in :compileKotlin).
        gradleFlags = ["-Pkotlin.compiler.execution.strategy=in-process"];

        # UTF-8 file names for the JVM: Gradle 9's HTML test report writes one directory
        # per test name ("0°", "O(n³)"), which fails under the build sandbox's POSIX locale.
        # glibc has C.UTF-8 built in; macOS has UTF-8 (roadmap item 48). Same setting as
        # the devenv (item 33).
        LC_CTYPE =
          if isLinux
          then "C.UTF-8"
          else "UTF-8";

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
        src = sourceOf [./frontend ./docs/api];

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
          hash = "sha256-xcyDkYjMO5ebgKAFuwHaa1GbE0wlZeNk/IBFAV2nvCE=";
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

      # The desktop app (roadmap item 50, ADR 0053): Electron on the same backend, UI and tools.
      desktop = import ./nix/desktop.nix {
        inherit pkgs nixpkgs sourceOf backend frontend whisperCpp ffmpeg;
      };
    in {
      packages =
        pkgs.lib.removeAttrs {
          inherit backend frontend desktop;
          # The desktop app as one self-contained executable (nix/desktop-bundle.nix, ADR 0053).
          desktop-bundle = import ./nix/desktop-bundle.nix {inherit pkgs desktop;};

          # Roadmap item 56: the release asset for the bundle's Vulkan-only Mesa on this system, with the
          # pin to paste into nix/desktop-artifacts.nix. The user builds it (the source Mesa is an
          # input, so it must be in the store) and uploads the tar to a `desktop-mesa-<n>` release of
          # this repository; agents never publish. `pin.nix` carries the sha256, the source's out path
          # and its direct references, printed with `cat result/pin.nix`.
          desktop-bundle-mesa-asset = import ./nix/release-asset.nix {
            inherit pkgs;
            source = (import ./nix/desktop-mesa.nix {inherit pkgs;}).source;
            name = "stt-bundle-mesa-asset";
            tag = "desktop-mesa-v1";
            key = "mesaVulkan";
          };

          # Roadmap item 62: the release asset for the audio-only ffmpeg on this system, with the pin to
          # paste into nix/desktop-artifacts.nix. The user builds it and uploads the tar to a
          # `ffmpegAssetTag` release of this repository; agents never publish. `pin.nix` carries the
          # sha256, the source's out path and its direct references, printed with `cat result/pin.nix`.
          # The darwin asset is a `.tar.gz`: the Gradle bootstrap and the DMG extract it on a stock
          # macOS host, which has no zstd (roadmap item 69); Linux keeps `.tar.zst` and its pin.
          desktop-ffmpeg-asset = import ./nix/release-asset.nix {
            inherit pkgs;
            source = (import ./nix/ffmpeg.nix {inherit pkgs;}).source;
            name = "stt-ffmpeg-asset";
            tag = ffmpegAssetTag;
            key = "ffmpeg";
            compression =
              if isLinux
              then "zst"
              else "gz";
          };

          # Roadmap item 63: the release asset for the pinned whisper-cpp on this system, with the pin
          # to paste into nix/desktop-artifacts.nix. The user builds it and uploads the tar to a
          # `desktop-whisper-cpp-v1` release of this repository; agents never publish. `pin.nix`
          # carries the sha256, the source's out path and its direct references, printed with
          # `cat result/pin.nix`.
          desktop-whisper-cpp-asset = import ./nix/release-asset.nix {
            inherit pkgs;
            source = (import ./nix/whisper-cpp.nix {inherit pkgs;}).source;
            name = "stt-whisper-cpp-asset";
            tag = whisperCppAssetTag;
            key = "whisperCpp";
          };

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
              # --run: STT_DATA_DIR unset or empty defaults to the platform's per-user data
              # directory (XDG on Linux, ~/Library/Application Support on macOS; roadmap item 16
              # amending ADR 0017 for the packaged app, item 48 for darwin), which the wrapper
              # creates. The doubled quotes escape the dollar signs below, so the wrapper (not
              # Nix) expands the variables.
              # --set-default for the models: the pinned whisper model (ADR 0008) and CAM++
              # model (ADR 0003) from nix/models.nix unless the user sets one (item 21's e2e
              # suite swaps in the tiny whisper model).
              # --prefix PATH: the backend runs whisper-cli, ffmpeg and setsid by name (ADR 0047);
              # the package's own tools come first, so a caller's PATH (e.g. the devenv's
              # whisper-cli) cannot replace them. The audio-only ffmpeg from the pinned
              # desktop-ffmpeg-v2 asset (roadmap item 62), the branch item 33's Gradle bootstrap
              # syncs to (8.1), not nixpkgs' default ffmpeg (9.0 at the locked rev).
              makeWrapper ${pkgs.jre}/bin/java $out/bin/stt-app \
                --run 'if [ -z "''${STT_DATA_DIR:-}" ]; then export STT_DATA_DIR=${dataDirDefault}; fi; ${pkgs.coreutils}/bin/mkdir -p "$STT_DATA_DIR"' \
                --set STT_STATIC_DIR "$out/share/stt-app/static" \
                --set-default STT_WHISPER_MODEL ${models.whisper} \
                --set-default STT_SPEAKER_MODEL ${models.speaker} \
                --prefix PATH : ${pkgs.lib.makeBinPath ([whisperCpp ffmpeg pkgs.coreutils] ++ pkgs.lib.optional isLinux pkgs.util-linux)} \
                --set CLASSPATH "$CP" \
                --add-flags "stt.MainKt"
              runHook postInstall
            '';
            meta = {
              description = "Speech-to-Text app (backend + bundled frontend)";
              mainProgram = "stt-app";
            };
          };

          # Image-only (roadmap item 19): the Docker image's root filesystem besides /nix/store,
          # with every store path known here. The Dockerfile copies this output's closure 1:1 to
          # /nix/store and its contents to /.
          # - entrypoint.sh exports the GPU userspace the host cannot lend the container, then
          #   execs the app (a native host brings its own, e.g. NixOS's /run/opengl-driver, so the
          #   app package does not carry it):
          #   - VK_ADD_DRIVER_FILES: Mesa's Vulkan drivers for Intel/AMD GPUs - the radeon (RADV)
          #     and intel (ANV, HASVK) ICDs only, no lavapipe, so a CPU "GPU" is never picked;
          #     additive, so a CDI-mounted NVIDIA ICD still loads. A renamed ICD fails the build.
          #   - LD_LIBRARY_PATH, only when /dev/nvidiactl exists: the libraries NVIDIA's
          #     CDI-mounted driver links (libGLX_nvidia needs libX11 and more; the CDI mount brings
          #     only the driver and glibc), nix/nvidia-driver-libs.nix — shared with the desktop
          #     bundle, which borrows a host's NVIDIA driver the same way.
          # - bin/sh: bash, for `docker compose exec stt-app sh`.
          # - usr/local/bin/curl: the compose healthcheck's tool.
          # - etc/passwd, etc/group, etc/nsswitch.conf: root and the app user stt (uid/gid 1000).
          docker-root = let
            vkIcds = map (icd: "${pkgs.mesa}/share/vulkan/icd.d/${icd}.x86_64.json") [
              "radeon_icd"
              "intel_icd"
              "intel_hasvk_icd"
            ];
            nvidiaLibPath = pkgs.lib.makeLibraryPath (import ./nix/nvidia-driver-libs.nix pkgs);
            entrypoint = pkgs.writeShellScript "stt-app-docker-entrypoint" ''
              export VK_ADD_DRIVER_FILES=${pkgs.lib.concatStringsSep ":" vkIcds}
              if [ -e /dev/nvidiactl ]; then
                export LD_LIBRARY_PATH=${nvidiaLibPath}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
              fi
              exec ${pkgs.lib.getExe self.packages.${system}.default} "$@"
            '';
          in
            pkgs.runCommand "stt-app-docker-root" {} ''
              for icd in ${pkgs.lib.concatStringsSep " " vkIcds}; do test -e "$icd"; done
              mkdir -p $out/bin $out/usr/local/bin $out/etc
              ln -s ${entrypoint} $out/entrypoint.sh
              ln -s ${pkgs.runtimeShell} $out/bin/sh
              ln -s ${pkgs.lib.getExe pkgs.curl} $out/usr/local/bin/curl
              printf 'root:x:0:0:root:/root:/bin/sh\nstt:x:1000:1000:stt:/home/stt:/bin/sh\n' > $out/etc/passwd
              printf 'root:x:0:\nstt:x:1000:\n' > $out/etc/group
              printf 'passwd: files\nshadow: files\ngroup: files\nhosts: files dns\nnetworks: files\nservices: files\n' > $out/etc/nsswitch.conf
            '';
          # `desktop-ffmpeg-asset` is not removed on darwin: the manual `macos-ffmpeg-asset` job builds
          # the aarch64-darwin asset with it (roadmap item 69), like `desktop-whisper-cpp-asset`.
        } (
          if isLinux
          then []
          else ["desktop" "desktop-bundle" "desktop-bundle-mesa-asset" "docker-root"]
        );

      # `nix run .` starts bin/stt-app (named by meta.mainProgram), on http://localhost:8080.
      # `nix run .#desktop` opens the desktop app; `.#desktop-bundle` unpacks and runs the
      # single-file bundle (roadmap item 50). Both are Linux-only (their inputs are Linux
      # Electron/JRE), so they are removed from the darwin sets (roadmap item 48).
      apps =
        pkgs.lib.removeAttrs {
          default = {
            type = "app";
            program = pkgs.lib.getExe self.packages.${system}.default;
            meta.description = "Start the Speech-to-Text app";
          };

          desktop = {
            type = "app";
            program = pkgs.lib.getExe self.packages.${system}.desktop;
            meta.description = "Open the Speech-to-Text desktop app";
          };

          desktop-bundle = {
            type = "app";
            program = pkgs.lib.getExe self.packages.${system}.desktop-bundle;
            meta.description = "Run the self-contained Speech-to-Text desktop bundle";
          };

          # Regenerates the committed versions.json from the flake's versions (ADR 0010: the
          # user runs this; writes to the checkout the command is run from).
          update-versions = {
            type = "app";
            program = "${pkgs.writeShellScript "update-versions" ''
              exec ${pkgs.jq}/bin/jq -S . ${versionsJson} > versions.json
            ''}";
            meta.description = "Regenerate the committed versions.json from the flake's versions";
          };

          # `nix run .#ffmpeg-asset-pin` / `nix run .#whisper-cpp-asset-pin` (roadmap items 62, 63 and
          # 69): the producer checks behind the asset builders. nix/asset-pin-check.nix builds each
          # check's script with the check as an input, so running the app builds it and the source it
          # asserts against; they are apps, not default checks. The ffmpeg one runs on both systems;
          # the whisper-cpp one is Linux-only.
          ffmpeg-asset-pin = {
            type = "app";
            program = "${assetPinChecks.ffmpeg-asset-pin.app}/bin/ffmpeg-asset-pin";
            meta.description = "Verify the desktop-ffmpeg-v2 asset pin against the built asset";
          };
          whisper-cpp-asset-pin = pkgs.lib.optionalAttrs isLinux {
            type = "app";
            program = "${assetPinChecks.whisper-cpp-asset-pin.app}/bin/whisper-cpp-asset-pin";
            meta.description = "Verify the desktop-whisper-cpp-v1 asset pin against the built asset";
          };

          # Roadmap item 69 (the root review's finding 1): the manual `macos-ffmpeg-asset` job's
          # fixture and app-command proofs, run on the binary that job built instead of the pinned
          # prebuilt, so a stale pin cannot stop the job before it packages and prints its asset. Same
          # scripts as the `ffmpeg-upload-formats` and `ffmpeg-app-uses` checks.
          ffmpeg-source-surface = let
            ffmpeg = (import ./nix/ffmpeg.nix {inherit pkgs;}).source;
          in {
            type = "app";
            program = "${pkgs.writeShellApplication {
              name = "ffmpeg-source-surface";
              runtimeInputs = [pkgs.bash pkgs.coreutils];
              text = ''
                ${ffmpegUploadFormats ffmpeg}
                ${ffmpegAppUses ffmpeg}
                echo "ffmpeg-source-surface: ok"
              '';
            }}/bin/ffmpeg-source-surface";
            meta.description = "Prove the upload-format and app-command fixtures on the audio-only ffmpeg source build";
          };

          # `nix run .#ffmpeg-audio-only` (roadmap item 62): the build-surface check, moved out of
          # `checks` so `nix flake check` compiles no ffmpeg source.
          ffmpeg-audio-only = {
            type = "app";
            program = "${pkgs.writeShellScriptBin "ffmpeg-audio-only" ''
              : ${ffmpegAudioOnlyCheck}
              echo "ffmpeg-audio-only: ok"
            ''}/bin/ffmpeg-audio-only";
            meta.description = "Verify the audio-only ffmpeg build's configure surface";
          };
        } (
          if isLinux
          then []
          else ["desktop" "desktop-bundle" "whisper-cpp-asset-pin"]
        );

      # `nix bundle --bundler .#desktop .#packages.x86_64-linux.desktop` produces the same file: the
      # bundler interface is `bundlers.<system>.<name> = drv: drv'`, and the derivation takes the
      # package to bundle as its argument. Linux-only, like the desktop package.

      # ADR 0036: the devenv's nixpkgs input is the flake's locked nixpkgs. `nix flake
      # check` fails when the revs drift; `devenv update` rewrites devenv.lock to
      # devenv.yaml's url. devenv.yaml is written as JSON on purpose, so the check is a
      # plain jq read (JSON is a subset of YAML; devenv's parser accepts it). The check
      # also reads devenv.lock, the file the devenv CLI actually evaluates: a lock that
      # lagged behind devenv.yaml would hand the shell a different nixpkgs than the flake's
      # (roadmap item 82: the lint and the shell must run the same actionlint). The check
      # reads only the three lock/pin files, so a nixpkgs bump or a devenv.yaml edit
      # (not the parts' sources) rebuilds it.
      checks =
        pkgs.lib.removeAttrs {
          devenv-pin-sync = pkgs.runCommand "devenv-pin-sync" {} ''
            flakeRev=$(
              ${pkgs.jq}/bin/jq -r '.nodes.nixpkgs.locked.rev' ${./flake.lock}
            )
            devenvRev=$(
              ${pkgs.jq}/bin/jq -r '.inputs.nixpkgs.url | split("/")[-1]' ${./devenv.yaml}
            )
            lockRev=$(
              ${pkgs.jq}/bin/jq -r '.nodes.nixpkgs.locked.rev' ${./devenv.lock}
            )
            if [ -z "$devenvRev" ]; then
              echo "devenv-pin-sync: devenv.yaml input \"nixpkgs\" must be a github:NixOS/nixpkgs/<rev> url" >&2
              exit 1
            fi
            if [ "$flakeRev" != "$devenvRev" ]; then
              echo "devenv-pin-sync: devenv.yaml pins nixpkgs $devenvRev but flake.lock has $flakeRev" >&2
              echo "run 'devenv update' (or fix devenv.yaml) and re-enter the shell" >&2
              exit 1
            fi
            if [ "$flakeRev" != "$lockRev" ]; then
              echo "devenv-pin-sync: devenv.lock evaluates nixpkgs $lockRev but flake.lock has $flakeRev" >&2
              echo "run 'devenv update' and re-enter the shell" >&2
              exit 1
            fi
            echo "$flakeRev" > $out
          '';

          # Roadmap item 33: the committed versions.json must be exactly what the flake
          # renders (nixpkgs-derived versions + nix/versions.nix + nix/models.nix). The
          # check fails on any drift - a nixpkgs bump, a tag/version change, an edited
          # hash - and the user regenerates with `nix run .#update-versions` (ADR 0010).
          # Reads only the version inputs, so a non-version change does not rerun it.
          versions-sync = pkgs.runCommand "versions-sync" {} ''
            ${pkgs.jq}/bin/jq -S . ${versionsJson} > expected.json
            ${pkgs.jq}/bin/jq -S . ${./versions.json} > committed.json
            if ! diff -u committed.json expected.json; then
              echo "versions-sync: versions.json drifted from the flake's versions (a pinned version or hash changed)." >&2
              echo "Run 'nix run .#update-versions' and commit the regenerated versions.json (and the wrapper, if the Gradle version changed)." >&2
              exit 1
            fi
            echo ok > $out
          '';

          # Roadmap item 50 (ADR 0053): the desktop package's closure holds no model. The app downloads
          # the models on first start or takes them from the store at their fixed-output paths, so the
          # package — and a `nix bundle` made from it — stays ~600 MB smaller. A pinned model is
          # recognised by its file name (the URL's last segment, the name fetchurl gives it), so a model
          # brought in any other way is caught too. $out is the closure's size report: every store path
          # with its size, largest first, and the total (`cat result` after building the check).
          desktop-model-free = let
            modelNames = map (pin: baseNameOf pin.url) (builtins.attrValues models.meta);
            modelPattern = pkgs.lib.concatMapStringsSep "|" pkgs.lib.escapeRegex modelNames;
            closure = pkgs.closureInfo {rootPaths = [desktop];};
          in
            pkgs.runCommand "desktop-model-free" {} ''
              found=$(grep -E -- '-(${modelPattern})$' ${closure}/store-paths || true)
              if [ -n "$found" ]; then
                echo "desktop-model-free: the desktop closure holds a pinned model:" >&2
                echo "$found" >&2
                exit 1
              fi
              xargs du -sb < ${closure}/store-paths | sort -rn \
                | awk '{ total += $1; printf "%9.1f MiB  %s\n", $1 / 1048576, $2 }
                       END { printf "%9.1f MiB  total (%d store paths)\n", total / 1048576, NR }' > $out
            '';

          # Roadmap item 62: the shipped audio-only ffmpeg's closure holds none of the GStreamer/GTK-4
          # cluster. The artifact is one static binary with no external libraries (item 61's headless
          # variant reached no sdl2-compat → sdl3 → pipewire → gstreamer → python3 chain either; measured
          # 2026-10-18 on the pre-switch stock ffmpeg_8: `nix why-depends
          # /nix/store/ss9dmg3n…-ffmpeg-8.1.2-bin …/python3-3.14.7`; that closure also held flite,
          # gst-plugins-bad and gtk4). The new check above pins this package as the one the desktop, the
          # server package and docker-root ship, so the group is unreachable from it in all three. A
          # package's own closure may still hold a member through an unrelated referrer (docker-root →
          # nixpkgs Mesa → python3), which is not the behavior this removes. Names are matched by prefix:
          # flite is "flite-2.2-lib" and gtk4 "gtk4-4.22.4", which a version-anchored pattern misses (the
          # item-50 lesson).
          ffmpeg-no-gstreamer-gtk = let
            lib = pkgs.lib;
            closure = pkgs.closureInfo {rootPaths = [ffmpeg];};
            group = ["python3" "gtk4" "gst-plugins-bad" "flite" "pipewire"];
            pattern = "-(${lib.concatStringsSep "|" group})-";
          in
            pkgs.runCommand "ffmpeg-no-gstreamer-gtk" {} ''
              found=$(grep -E -- '${pattern}' ${closure}/store-paths || true)
              if [ -n "$found" ]; then
                echo "ffmpeg-no-gstreamer-gtk: the GStreamer/GTK-4 cluster is in the shipped ffmpeg's closure:" >&2
                echo "$found" >&2
                exit 1
              fi
              echo ok > $out
            '';

          # Roadmap item 62 (test-list item 1) and item 69 (test-list item 3): every build ships the same
          # audio-only ffmpeg as the pinned release asset, not a source build — on Linux the desktop's
          # tools (and so the bundle's) and the server package's wrapper PATH (and so docker-root's
          # closure); on darwin the server package alone, since the desktop package is Linux-only and the
          # macOS app is the DMG. The devenv shell uses the same import.
          # nix/artifact-everywhere-check.nix carries the assertions the two artifacts share.
          ffmpeg-audio-only-everywhere = let
            lib = pkgs.lib;
            artifacts = import ./nix/desktop-artifacts.nix;
            ffmpegArtifact = import ./nix/ffmpeg.nix {inherit pkgs;};
            desktop = self.packages.${system}.desktop or null;
            server = self.packages.${system}.default;
          in
            assert lib.assertMsg (desktop == null || desktop.ffmpeg.outPath == ffmpegArtifact.ffmpeg.outPath)
            "ffmpeg-audio-only-everywhere: the desktop ships ${desktop.ffmpeg.outPath}, not the prebuilt ${ffmpegArtifact.ffmpeg.outPath}";
              import ./nix/artifact-everywhere-check.nix {
                inherit pkgs desktop server;
                artifact = ffmpegArtifact.ffmpeg;
                source = ffmpegArtifact.source;
                pinned = artifacts.ffmpeg.${system} or null;
                key = "ffmpeg";
                subject = "ffmpeg";
                tool = "ffmpeg";
                roots = {default = server;} // lib.optionalAttrs isLinux {docker-root = self.packages.${system}.docker-root;};
                name = "ffmpeg-audio-only-everywhere";
              };

          # Roadmap item 62 (test-list item 3; the review's two additions): every extension the app accepts
          # decodes to the canonical FLAC through the shipped artifact, and the proof cannot drift from
          # `allowed-extensions.json`. nix/ffmpeg-upload-formats.sh validates each result from its own
          # STREAMINFO — no ffprobe, which the artifact does not ship. Runs on darwin too (roadmap item
          # 69): the manual `macos-ffmpeg-asset` job builds it against the binary it packages.
          ffmpeg-upload-formats = let
            ffmpeg = (import ./nix/ffmpeg.nix {inherit pkgs;}).ffmpeg;
          in
            pkgs.runCommand "ffmpeg-upload-formats" {
              nativeBuildInputs = [pkgs.bash pkgs.coreutils];
            } ''
              ${ffmpegUploadFormats ffmpeg}
              echo ok > $out
            '';

          # Roadmap item 62 (test-list item 4): the app's other ffmpeg commands still work with the shipped
          # artifact — f32le PCM decode, silencedetect to the null muxer, the atrim window cut and the V10
          # concat backfill. nix/ffmpeg-app-uses.sh mirrors the app's argument lists; the atrim and concat
          # results are checked from their FLAC STREAMINFO, without ffprobe. Runs on darwin too (roadmap
          # item 69).
          ffmpeg-app-uses = let
            ffmpeg = (import ./nix/ffmpeg.nix {inherit pkgs;}).ffmpeg;
          in
            pkgs.runCommand "ffmpeg-app-uses" {
              nativeBuildInputs = [pkgs.bash pkgs.coreutils];
            } ''
              ${ffmpegAppUses ffmpeg}
              echo ok > $out
            '';

          # Roadmap item 74 (test-list item 1): every language in docs/api/languages.json has every
          # character of its sample text in the bundle's shipped fonts, so a language with no font
          # fails the check instead of rendering as missing glyphs. The fonts come from the module the
          # bundle composes (nix/bundle-fonts.nix), so the check cannot drift from what ships.
          font-coverage = import ./nix/font-coverage.nix {
            inherit pkgs;
            fontsConf = (import ./nix/bundle-fonts.nix {inherit pkgs;}).fontsConf;
          };

          # Roadmap item 74 (test-list item 2): the bundle's fontconfig resolves Han text by the
          # language it is marked with, so a Japanese transcript gets IBM Plex Sans JP's glyph forms
          # rather than the Simplified Chinese face's, and SC and TC keep their own faces.
          font-forms = import ./nix/font-forms.nix {
            inherit pkgs;
            fontsConf = (import ./nix/bundle-fonts.nix {inherit pkgs;}).fontsConf;
          };

          # Roadmap item 74 (test-list item 6): the Nix package and the bundle carry matching .desktop
          # entries and the app's mark as their icon; the bundle installs its own only when asked
          # (`--install-desktop-entry`). Builds both, like the bundle e2e does.
          desktop-entries = let
            identity = import ./nix/desktop-identity.nix {inherit (pkgs) lib;};
          in
            import ./nix/desktop-entries.nix {
              inherit pkgs;
              desktop = self.packages.${system}.desktop;
              bundle = self.packages.${system}.desktop-bundle;
              inherit (identity) desktopName windowClass icon;
            };

          # Roadmap item 62 (test-list item 5): the ffmpeg artifact's integrity gates, like the Mesa ones —
          # a registered asset that is missing or does not match its sha256 fails naming the asset, url and
          # sha256; an asset pinned to another source build fails at evaluation naming both paths; the
          # stray-path scan fails on an unregistered path; the real prebuilt names no old build path, no
          # stray path, and sits in its own runtime closure (its pinned references are empty).
          # nix/artifact-integrity-check.nix carries the gates the two artifacts share. Runs on both
          # systems (roadmap item 69): the fixture mirrors the pinned asset's archive type.
          ffmpeg-artifact-integrity = import ./nix/artifact-integrity-check.nix {
            inherit pkgs;
            importWith = artifacts: import ./nix/ffmpeg.nix {inherit pkgs artifacts;};
            attr = "ffmpeg";
            pin = (import ./nix/desktop-artifacts.nix).ffmpeg.${system};
            fixtureName =
              if isLinux
              then "ffmpeg-asset-fixture.tar.zst"
              else "ffmpeg-asset-fixture.tar.gz";
            name = "ffmpeg-artifact-integrity";
          };

          # Roadmap item 69 (test-list item 8): a mismatched pin — wrong storePath, the old tag, or a
          # sha256 that is not the asset's — fails the asset-pin check naming the asset file and the
          # pin's url. Fixtures stand in for the source build and the asset, so nothing is compiled; the
          # two pin gates are read at evaluation, the sha256 gate runs the check's own verification.
          ffmpeg-asset-pin-mismatch = let
            lib = pkgs.lib;
            source = pkgs.writeText "ffmpeg-asset-pin-fixture-source" "";
            # The name nix/release-asset.nix gives this build's asset, spelled out independently: the
            # source's name, the system, and the compression (zst on Linux, gz on darwin).
            expectedFile = "${source.name}-${system}.tar.${
              if isLinux
              then "zst"
              else "gz"
            }";
            asset = pkgs.writeTextDir expectedFile "not the release asset" // {file = expectedFile;};
            checkWith = pin:
              import ./nix/asset-pin-check.nix {
                inherit pkgs source asset pin;
                tag = ffmpegAssetTag;
                key = "ffmpeg";
                name = "ffmpeg-asset-pin";
              };
            url = file: "https://github.com/lulu-berlin/speech-to-text-app/releases/download/${ffmpegAssetTag}/${file}";
            # Names the fixture build's own asset under the current tag; its sha256 is not the asset's.
            pin = {
              url = url expectedFile;
              sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
              storePath = builtins.unsafeDiscardStringContext source.outPath;
              references = [];
            };
            rejects = label: bad: let
              error = (checkWith bad).pinError;
            in
              assert lib.assertMsg (error != null) "ffmpeg-asset-pin-mismatch: ${label} was accepted";
              assert lib.assertMsg (lib.hasInfix expectedFile error && lib.hasInfix bad.url error)
              "ffmpeg-asset-pin-mismatch: the error for ${label} does not name ${expectedFile} and ${bad.url}; it reads: ${error}"; true;
            wrongSha256 = checkWith pin;
          in
            assert rejects "a wrong storePath" (pin // {storePath = "/nix/store/00000000000000000000000000000000-ffmpeg-audio-only-8.1.2";});
            assert rejects "the old tag" (pin // {url = lib.replaceStrings ["/${ffmpegAssetTag}/"] ["/desktop-ffmpeg-v1/"] (url expectedFile);});
            assert rejects "another file's url" (pin // {url = url (lib.replaceStrings [".tar.zst" ".tar.gz"] [".tar.gz" ".tar.zst"] expectedFile);});
            # A url whose basename merely starts with the asset's name (a sidecar) named another file.
            assert rejects "a sidecar url" (pin // {url = url "${expectedFile}.sha256";});
            assert lib.assertMsg (wrongSha256.pinError == null) "ffmpeg-asset-pin-mismatch: the sha256 case failed at evaluation: ${toString wrongSha256.pinError}";
              pkgs.runCommand "ffmpeg-asset-pin-mismatch" {nativeBuildInputs = [pkgs.gnugrep];} ''
                if ${lib.getExe wrongSha256.verifyAsset} ${asset}/${expectedFile} 2> err; then
                  echo "ffmpeg-asset-pin-mismatch: a sha256 that is not the asset's was accepted" >&2
                  exit 1
                fi
                for expected in ${lib.escapeShellArgs [expectedFile pin.url]}; do
                  grep -qF -- "$expected" err || {
                    echo "ffmpeg-asset-pin-mismatch: the sha256 error does not name $expected; it reads: $(cat err)" >&2
                    exit 1
                  }
                done
                echo ok > $out
              '';

          # Roadmap item 62 (test-list item 6): the artifact's runtime closure is the static binary alone,
          # holds none of the video libraries the slim build drops, and it is the only ffmpeg in the
          # server package's, docker-root's and the bundle's closures (the whisper-cpp fix). $out is the
          # size report; the official item-61 comparison is `nix path-info -S` run by the user.
          ffmpeg-closure-slim = let
            lib = pkgs.lib;
            ffmpeg = (import ./nix/ffmpeg.nix {inherit pkgs;}).ffmpeg;
            roots = {
              default = self.packages.${system}.default;
              docker-root = self.packages.${system}.docker-root;
              desktop-bundle = self.packages.${system}.desktop-bundle;
            };
            closures = lib.mapAttrs (_: root: pkgs.closureInfo {rootPaths = [root];}) roots;
            artifactClosure = pkgs.closureInfo {rootPaths = [ffmpeg];};
            dropped = ["x265" "libvpx" "libaom" "svt-av1" "dav1d" "x264"];
            pattern = "-(${lib.concatStringsSep "|" (map lib.escapeRegex dropped)})-";
          in
            pkgs.runCommand "ffmpeg-closure-slim" {
              nativeBuildInputs = [pkgs.coreutils pkgs.gnugrep pkgs.gawk];
            } ''
              fail() {
                echo "ffmpeg-closure-slim: $1" >&2
                exit 1
              }
              [ "$(wc -l < ${artifactClosure}/store-paths)" = 1 ] ||
                fail "the artifact's runtime closure is not one path: $(cat ${artifactClosure}/store-paths)"
              found=$(grep -E -- '${pattern}' ${artifactClosure}/store-paths || true)
              [ -z "$found" ] || fail "the artifact's closure holds a dropped video library: $found"
              ${lib.concatStrings (lib.mapAttrsToList (name: closure: ''
                  others=$(grep -E -- '-ffmpeg-' ${closure}/store-paths | grep -vxF ${ffmpeg} || true)
                  [ -z "$others" ] || fail "the ${name} closure holds another ffmpeg: $others"
                '')
                closures)}
              {
                echo "ffmpeg artifact: $(du -sb ${ffmpeg} | cut -f1) bytes"
                ${lib.concatStrings (lib.mapAttrsToList (name: closure: ''
                  echo "${name} closure: $(xargs du -sb < ${closure}/store-paths | awk '{s += $1} END {print s + 0}') bytes"
                '')
                closures)}
              } > $out
              cat $out
            '';

          # Roadmap item 56: the bundle's Vulkan-only Mesa may come from a prebuilt release asset only on
          # a system that has one registered; any other system builds it from source. Checked for
          # aarch64-linux, which has none: its bundle Mesa must be the source derivation itself.
          # Evaluation only (nixpkgs' cross package set), so nothing for aarch64 is built.
          desktop-mesa-unregistered-system = let
            aarch64 = import ./nix/desktop-mesa.nix {pkgs = pkgs.pkgsCross.aarch64-multiplatform;};
          in
            assert pkgs.lib.assertMsg (aarch64.mesa.outPath == aarch64.source.outPath)
            "desktop-mesa-unregistered-system: aarch64-linux has no artifact entry, so its bundle Mesa must be the source derivation ${aarch64.source.outPath}, got ${aarch64.mesa.outPath}";
              pkgs.runCommand "desktop-mesa-unregistered-system" {} ''
                echo ok > $out
              '';

          # Roadmap item 56: a registered Mesa artifact that is missing or does not match its sha256 pin
          # fails, naming the asset and its pin (url and sha256), and the registered Mesa never builds the
          # source derivation. Offline and deterministic: the asset's own fetch step runs against a
          # `file://` fixture in the store, once under a deliberately wrong sha256 and once at a URL with
          # no file behind it; both must fail and name the pin. And, checked at evaluation, the source
          # Mesa's .drv is a direct input of none of the registered Mesa's own derivations (the unpack,
          # the asset, its fetch script). Not the whole build graph: exporting a .drv's closure makes Nix
          # build every output in it, the source Mesa's included.
          desktop-mesa-artifact-integrity = let
            fixture = pkgs.writeText "stt-bundle-mesa-vulkan-fixture.tar.zst" "not the release asset";
            registered = url: let
              mesas = import ./nix/desktop-mesa.nix {
                inherit pkgs;
                artifacts.mesaVulkan.${system} = {
                  inherit url;
                  # Deliberately wrong: no file hashes to all zeros.
                  sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
                  # A pin is a literal (as in nix/desktop-artifacts.nix), so no context on the path.
                  storePath = builtins.unsafeDiscardStringContext mesas.source.outPath;
                  references = [];
                };
              };
            in
              mesas;
            cases = {
              mismatched = registered "file://${fixture}";
              missing = registered "file://${fixture}.missing";
            };
            expectFailure = label: mesas: let
              asset = mesas.mesa.asset;
            in ''
              if out=$TMPDIR/${label} ${asset.fetch} 2> ${label}.err; then
                echo "desktop-mesa-artifact-integrity: the ${label} artifact was accepted" >&2
                exit 1
              fi
              for expected in ${pkgs.lib.escapeShellArgs [asset.name asset.url asset.sha256]}; do
                if ! grep -qF -- "$expected" ${label}.err; then
                  echo "desktop-mesa-artifact-integrity: the ${label} artifact's error does not name '$expected':" >&2
                  cat ${label}.err >&2
                  exit 1
                fi
              done
            '';
            directInputs = drv: builtins.attrNames (builtins.getContext (builtins.toJSON drv.drvAttrs));
            sourceDrv = builtins.unsafeDiscardStringContext cases.mismatched.source.drvPath;
            registeredMesa = cases.mismatched.mesa;
            ownDerivations = [registeredMesa registeredMesa.asset registeredMesa.asset.fetch];
          in
            assert pkgs.lib.assertMsg (!pkgs.lib.any (drv: pkgs.lib.elem sourceDrv (directInputs drv)) ownDerivations)
            "desktop-mesa-artifact-integrity: a registered Mesa's own derivations depend on the source Mesa ${sourceDrv}";
              pkgs.runCommand "desktop-mesa-artifact-integrity" {} ''
                ${pkgs.lib.concatStrings (pkgs.lib.mapAttrsToList expectFailure cases)}
                echo ok > $out
              '';

          # Roadmap item 56: a registered Mesa artifact made from another source build than this flake's —
          # its pinned storePath is not the source Mesa's out path, as after a nixpkgs bump or an edit of
          # the override — fails at evaluation, so nothing is fetched or compiled. Nix cannot read an
          # evaluation error's text, so nix/desktop-mesa.nix also exposes the text it throws (`pinError`),
          # which must name the asset, its url, sha256 and storePath, and the source's out path.
          desktop-mesa-stale-pin = let
            lib = pkgs.lib;
            fixture = pkgs.writeText "stt-bundle-mesa-vulkan-fixture.tar.zst" "not the release asset";
            pin = {
              url = "file://${fixture}";
              sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
              # Deliberately not the source Mesa's out path.
              storePath = "/nix/store/00000000000000000000000000000000-stt-bundle-mesa-vulkan-26.2.4";
              references = [];
            };
            mesas = import ./nix/desktop-mesa.nix {
              inherit pkgs;
              artifacts.mesaVulkan.${system} = pin;
            };
            evaluated = builtins.tryEval mesas.mesa.outPath;
            message = mesas.pinError or "";
            named = map builtins.unsafeDiscardStringContext [
              (baseNameOf pin.url)
              pin.url
              pin.sha256
              pin.storePath
              mesas.source.outPath
            ];
            unnamed = lib.filter (expected: !lib.hasInfix expected message) named;
          in
            assert lib.assertMsg (!evaluated.success)
            "desktop-mesa-stale-pin: a pin made from ${pin.storePath} evaluated to ${toString evaluated.value}";
            assert lib.assertMsg (unnamed == [])
            "desktop-mesa-stale-pin: the stale pin's error does not name ${lib.concatStringsSep ", " unnamed}; it reads: ${message}";
              pkgs.runCommand "desktop-mesa-stale-pin" {} ''
                echo ok > $out
              '';

          # Roadmap item 56: on x86_64-linux the bundle's Vulkan-only Mesa is the pinned release asset, not
          # the source build, and only that system is registered; a system with no entry builds from
          # source (checked above). Built, it must keep the three Vulkan drivers with their manifests
          # pointing into itself, name no store path of the build it was made from, refer only to its pins
          # and itself, and carry no LLVM.
          desktop-mesa-prebuilt = let
            lib = pkgs.lib;
            artifacts = import ./nix/desktop-artifacts.nix;
            registered = builtins.attrNames artifacts.mesaVulkan;
            pinned = artifacts.mesaVulkan.${system} or null;
            mesas = import ./nix/desktop-mesa.nix {inherit pkgs;};
            directInputs = drv: builtins.attrNames (builtins.getContext (builtins.toJSON drv.drvAttrs));
            sourceDrv = builtins.unsafeDiscardStringContext mesas.source.drvPath;
            references = map (p: lib.unsafeDiscardStringContext "${p}") pinned.references;
            mesaIcds = "${mesas.mesa}/share/vulkan/icd.d";
          in
            assert lib.assertMsg (registered == [system])
            "desktop-mesa-prebuilt: this item registers only ${system}, but nix/desktop-artifacts.nix registers ${lib.concatStringsSep ", " registered}";
            assert lib.assertMsg (pinned != null)
            "desktop-mesa-prebuilt: nix/desktop-artifacts.nix has no mesaVulkan.${system} entry, so the bundle Mesa is the source build ${mesas.mesa.outPath}";
            assert lib.assertMsg (mesas.mesa.outPath != mesas.source.outPath)
            "desktop-mesa-prebuilt: the bundle's ${system} Mesa is the source derivation ${mesas.source.outPath}, not the prebuilt asset";
            assert lib.assertMsg (!lib.any (drv: lib.elem sourceDrv (directInputs drv)) [mesas.mesa mesas.mesa.asset mesas.mesa.asset.fetch])
            "desktop-mesa-prebuilt: the prebuilt Mesa's own derivations depend on the source Mesa ${sourceDrv}";
              pkgs.runCommand "desktop-mesa-prebuilt" {nativeBuildInputs = [pkgs.jq];} ''
                fail() {
                  echo "desktop-mesa-prebuilt: $1" >&2
                  exit 1
                }
                for driver in radeon intel nouveau; do
                  manifest=${mesaIcds}/''${driver}_icd.x86_64.json
                  library=$(jq -r .ICD.library_path "$manifest")
                  case "$library" in
                    ${mesas.mesa}/lib/*) [ -f "$library" ] || fail "$manifest names $library, which is missing" ;;
                    *) fail "$manifest names $library, outside the prebuilt Mesa" ;;
                  esac
                done
                if grep -rlaF ${lib.escapeShellArg pinned.storePath} ${mesas.mesa}; then
                  fail "the prebuilt Mesa still names ${pinned.storePath}, the build it was made from"
                fi
                if ! stray=$(bash ${./nix/store-path-scan.sh} ${mesas.mesa} ${lib.escapeShellArgs references}); then
                  fail "the prebuilt Mesa names store paths that are not pinned references: $stray"
                fi
                # Nix records references only among a derivation's inputs, so a pin that is not an input
                # silently drops out of the closure the bundle's payload is built from: assert each one
                # is there. (Found 2026-10-18: ten of the thirteen were missing from the bundle.)
                for reference in ${lib.escapeShellArgs references}; do
                  grep -qxF "$reference" ${pkgs.closureInfo {rootPaths = [mesas.mesa];}}/store-paths ||
                    fail "the prebuilt Mesa's closure is missing its pinned reference $reference"
                done
                echo ok > $out
              '';

          # Roadmap item 63 (test-list item 7): a system with no registered whisper-cpp artifact ships
          # the source build and reports no stale pin. Checked for aarch64-linux, which has no entry
          # (items 48/49 add their systems' artifacts with them). Evaluation only (nixpkgs' cross
          # package set), so nothing for aarch64 is built.
          whisper-cpp-unregistered-system = let
            aarch64 = import ./nix/whisper-cpp.nix {pkgs = pkgs.pkgsCross.aarch64-multiplatform;};
          in
            assert pkgs.lib.assertMsg (aarch64.whisperCpp.outPath == aarch64.source.outPath)
            "whisper-cpp-unregistered-system: aarch64-linux has no artifact entry, so its whisperCpp must be the source derivation ${aarch64.source.outPath}, got ${aarch64.whisperCpp.outPath}";
            assert pkgs.lib.assertMsg (aarch64.pinError == null)
            "whisper-cpp-unregistered-system: a system without a pin reported a stale-pin error: ${aarch64.pinError}";
              pkgs.runCommand "whisper-cpp-unregistered-system" {} ''
                echo ok > $out
              '';

          # Roadmap item 63 (test-list item 1): every x86_64-linux build ships the same prebuilt whisper-cpp —
          # the desktop's tools (and so the bundle's) and the server package's wrapper PATH (and so
          # docker-root's closure) — as the pinned release asset, not a source build. The devenv shell
          # imports the same nix/whisper-cpp.nix; the empty-store dry-run proves the build graphs.
          # nix/artifact-everywhere-check.nix carries the shared assertions, including the source build's
          # absence from each closure.
          whisper-cpp-everywhere = let
            artifacts = import ./nix/desktop-artifacts.nix;
            whisperCppArtifact = import ./nix/whisper-cpp.nix {inherit pkgs;};
          in
            import ./nix/artifact-everywhere-check.nix {
              inherit pkgs;
              artifact = whisperCppArtifact.whisperCpp;
              source = whisperCppArtifact.source;
              pinned = artifacts.whisperCpp.${system} or null;
              key = "whisperCpp";
              subject = "whisper-cpp";
              tool = "whisper-cli";
              desktop =
                if isLinux
                then self.packages.${system}.desktop
                else null;
              server = self.packages.${system}.default;
              # darwin ships no desktop, bundle or docker-root: the server package is the root there.
              roots =
                {server = self.packages.${system}.default;}
                // pkgs.lib.optionalAttrs isLinux {
                  docker-root = self.packages.${system}.docker-root;
                  desktop = self.packages.${system}.desktop;
                  desktop-bundle = self.packages.${system}.desktop-bundle;
                };
              name = "whisper-cpp-everywhere";
            };

          # Roadmap item 63 (test-list item 5): the whisper-cpp artifact's integrity gates, like the
          # ffmpeg ones — a registered asset that is missing or does not match its sha256 fails naming
          # the asset, url and sha256; an asset pinned to another source build fails at evaluation naming
          # both paths; the stray-path scan fails on an unregistered path; the real prebuilt names no old
          # build path, no stray path, sits in its own runtime closure, and every pinned reference is in
          # that closure. nix/artifact-integrity-check.nix carries the gates the two artifacts share.
          whisper-cpp-artifact-integrity = import ./nix/artifact-integrity-check.nix {
            inherit pkgs;
            importWith = artifacts: import ./nix/whisper-cpp.nix {inherit pkgs artifacts;};
            attr = "whisperCpp";
            pin = (import ./nix/desktop-artifacts.nix).whisperCpp.${system};
            fixtureName = "whisper-cpp-asset-fixture.tar.zst";
            name = "whisper-cpp-artifact-integrity";
          };

          # Roadmap item 63 (test-list item 6): every system the flake declares has a pinned whisper-cpp
          # artifact, so adding a platform (items 48, 49) cannot land without its asset. darwin's pin
          # (roadmap item 48's follow-up) is the static Metal build the Gradle bootstrap downloads.
          whisper-cpp-artifact-policy = let
            artifacts = import ./nix/desktop-artifacts.nix;
            missing = builtins.filter (s: (artifacts.whisperCpp.${s} or null) == null) systems;
          in
            assert pkgs.lib.assertMsg (missing == [])
            "whisper-cpp-artifact-policy: no pinned whisper-cpp artifact for ${pkgs.lib.concatStringsSep ", " missing} (nix/desktop-artifacts.nix whisperCpp)";
              pkgs.runCommand "whisper-cpp-artifact-policy" {} ''
                echo ok > $out
              '';

          # Roadmap item 63 (test-list item 3): the shipped prebuilt whisper-cpp's closure holds none of
          # the SDL2 -> sdl2-compat -> sdl3 -> GTK/GStreamer -> flite/pipewire chain the build cut with
          # `withSDL = false`, and no ffmpeg at all — nixpkgs' or the app's: `withFFmpegSupport = false`
          # leaves whisper-cli on its bundled miniaudio. Names are matched by prefix (flite is
          # "flite-2.2-lib", gtk4 "gtk4-4.22.4") — the item-50 lesson. Also proven for the darwin source
          # Metal build (roadmap item 48), which keeps the same recipe.
          whisper-cpp-closure-slim = let
            lib = pkgs.lib;
            whisperCpp = (import ./nix/whisper-cpp.nix {inherit pkgs;}).whisperCpp;
            closure = pkgs.closureInfo {rootPaths = [whisperCpp];};
            group = [
              "sdl2"
              "sdl2-compat"
              "sdl3"
              "gtk3"
              "gtk4"
              "gstreamer"
              "gst-plugins-base"
              "gst-plugins-bad"
              "flite"
              "freepats"
              "chromaprint"
              "pipewire"
              "python3"
              "zenity"
              "libadwaita"
            ];
            pattern = "-(${lib.concatStringsSep "|" (map lib.escapeRegex group)})-";
          in
            pkgs.runCommand "whisper-cpp-closure-slim" {} ''
              found=$(grep -E -- '${pattern}' ${closure}/store-paths || true)
              if [ -n "$found" ]; then
                echo "whisper-cpp-closure-slim: the SDL/GTK/GStreamer cluster is in the shipped whisper-cpp's closure:" >&2
                echo "$found" >&2
                exit 1
              fi
              found=$(grep -E -- '-ffmpeg-' ${closure}/store-paths || true)
              if [ -n "$found" ]; then
                echo "whisper-cpp-closure-slim: an ffmpeg is in the shipped whisper-cpp's closure:" >&2
                echo "$found" >&2
                exit 1
              fi
              echo ok > $out
            '';

          # Roadmap item 63 (test-list item 2): the shipped whisper-cli is the pinned 1.8.4 build (Vulkan
          # on Linux, Metal on darwin — roadmap item 48). `--help` succeeds, and it transcribes the
          # committed FLAC fixture to the committed expected text. Provenance of
          # nix/fixtures/whisper-cpp-sample-transcript.txt: captured 2026-10-19 from the source build
          # /nix/store/n2vf0kiskxmm9mfrs5mcla5q1k7zqpmi-whisper-cpp-1.8.4/bin/whisper-cli with
          # ggml-tiny.en.bin (sha256 921e4cf8…, nix/models.nix) and
          # `-m <model> -f backend/src/test/resources/upload-formats/sample.flac -nt`; the artifact under
          # test did not produce that file. The check runs the shipped artifact only.
          whisper-cpp-behaves = let
            whisperCpp = (import ./nix/whisper-cpp.nix {inherit pkgs;}).whisperCpp;
            fixture = ./backend/src/test/resources/upload-formats/sample.flac;
            expected = ./nix/fixtures/whisper-cpp-sample-transcript.txt;
          in
            pkgs.runCommand "whisper-cpp-behaves" {
              nativeBuildInputs = [pkgs.coreutils pkgs.diffutils];
            } ''
              fail() {
                echo "whisper-cpp-behaves: $1" >&2
                exit 1
              }
              ${whisperCpp}/bin/whisper-cli --help >/dev/null ||
                fail "the shipped whisper-cli --help failed"
              ${whisperCpp}/bin/whisper-cli -m ${models.whisperTinyEn} -f ${fixture} -nt > transcript.txt 2> whisper.log ||
                fail "the shipped whisper-cli failed to transcribe ${fixture}: $(cat whisper.log)"
              diff -u ${expected} transcript.txt ||
                fail "the shipped whisper-cli transcribed other text than the committed expected text"
              echo ok > $out
            '';

          # Roadmap item 63 (test-list item 8): the bundle, the server package and docker-root closures are
          # measured against item 62's final recorded du numbers (docs/specs/audio-only-ffmpeg.md, the
          # 2026-10-19 rework): the artifact switch replaces one closure path with its identical re-linked
          # copy, so none may grow. $out is the report; the official `nix path-info -S` comparison stays
          # the user's run.
          whisper-cpp-closure-size = let
            lib = pkgs.lib;
            roots = {
              default = self.packages.${system}.default;
              docker-root = self.packages.${system}.docker-root;
              desktop-bundle = self.packages.${system}.desktop-bundle;
            };
            # The closure bounds, each naming its measurement. Item 62's final du numbers
            # (docs/specs/audio-only-ffmpeg.md, the 2026-10-19 rework) set the artifact-switch
            # baseline; item 48's platform layer (ProcessPlatform.kt, the darwin signal
            # constants) grows the backend jar by 4462 bytes, measured on CI 2026-10-20 (run
            # 39356387395). The same jar drv is in docker-root, so its bound shares the delta;
            # the bundle's zstd payload is measured on the first run that reports it. The
            # script measures every closure before asserting, so one failing run names every
            # closure that moved, not just the first.
            bounds = {
              default = 1771164528;
              docker-root = 2805349550;
              desktop-bundle = 1564784978;
            };
            closures = lib.mapAttrs (_: root: pkgs.closureInfo {rootPaths = [root];}) roots;
          in
            pkgs.runCommand "whisper-cpp-closure-size" {
              nativeBuildInputs = [pkgs.coreutils pkgs.gawk];
            } ''
              fail() {
                echo "whisper-cpp-closure-size: $1" >&2
                exit 1
              }
              failed=0
              {
                ${lib.concatStrings (lib.mapAttrsToList (name: closure: let
                  bound = toString bounds.${name};
                in ''
                  size=$(xargs du -sb < ${closure}/store-paths | awk '{s += $1} END {print s + 0}')
                  echo "${name} closure: $size bytes (bound: ${bound} bytes)"
                  [ "$size" -le ${bound} ] || failed=1
                '')
                closures)}
              } | tee sizes.txt
              if [ "$failed" = 1 ]; then
                fail "a closure grew above its bound - the sizes above name every one that moved"
              fi
              cp sizes.txt $out
              cat sizes.txt
            '';

          # Roadmap item 63 (discovered, found in the item-1 cycle): the flat asset fetch is accepted by
          # the current Nix, which scans fixed-output outputs for references, because it discards them and
          # takes its output path from the structured-attrs shell file. The tar's bytes name store paths,
          # yet its own store path carries none.
          whisper-cpp-asset-fetch = let
            asset = (import ./nix/whisper-cpp.nix {inherit pkgs;}).whisperCpp.asset;
          in
            pkgs.runCommand "whisper-cpp-asset-fetch" {
              __structuredAttrs = true;
              nativeBuildInputs = [pkgs.coreutils pkgs.jq];
              exportReferencesGraph.graph = [asset];
            } ''
              fail() {
                echo "whisper-cpp-asset-fetch: $1" >&2
                exit 1
              }
              [ -s ${asset} ] || fail "the fetched asset is missing"
              references=$(${pkgs.jq}/bin/jq -r --arg self ${asset} \
                '.graph[] | select(.path == $self) | .references[]' $NIX_ATTRS_JSON_FILE)
              [ -z "$references" ] || fail "the fetched asset carries store references: $references"
              echo ok > $out
            '';

          # `ffmpeg-upload-formats` and `ffmpeg-app-uses` are not removed on darwin: they prove the
          # darwin binary's behavior on every accepted fixture and on the app's commands (roadmap item
          # 69), on the same binary the desktop ships.
        } (
          if isLinux
          then []
          else ["versions-sync" "desktop-model-free" "ffmpeg-no-gstreamer-gtk" "ffmpeg-closure-slim" "desktop-mesa-unregistered-system" "desktop-mesa-artifact-integrity" "desktop-mesa-stale-pin" "desktop-mesa-prebuilt" "whisper-cpp-unregistered-system" "whisper-cpp-closure-size" "font-coverage" "font-forms" "desktop-entries"]
        );
    };
  in {
    packages = nixpkgs.lib.genAttrs systems (system: (mkSystem system).packages);
    apps = nixpkgs.lib.genAttrs systems (system: (mkSystem system).apps);
    checks = nixpkgs.lib.genAttrs systems (system: (mkSystem system).checks);
    bundlers.x86_64-linux.desktop = drv:
      import ./nix/desktop-bundle.nix {
        pkgs = nixpkgs.legacyPackages.x86_64-linux;
        desktop = drv;
      };
  };
}
