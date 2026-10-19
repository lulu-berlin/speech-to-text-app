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
    system = "x86_64-linux";
    pkgs = nixpkgs.legacyPackages.${system};
    models = import ./nix/models.nix {inherit pkgs;};
    whisperCpp = import ./nix/whisper-cpp.nix {inherit pkgs;};

    # The app's ffmpeg (roadmap item 62): the audio-only static build, shipped as the pinned
    # prebuilt artifact on x86_64-linux (nix/ffmpeg.nix); the version manifest reads the source
    # build's version (8.1.2, rendered as 8.1).
    ffmpegArtifact = import ./nix/ffmpeg.nix {inherit pkgs;};
    ffmpeg = ffmpegArtifact.ffmpeg;

    # The release tag of the audio-only ffmpeg asset. The enable list changes the source build's out
    # path, which invalidates the pinned asset, so a changed build gets a new tag (v2 after the
    # audio-decoder rework; v1 is the first build).
    ffmpegAssetTag = "desktop-ffmpeg-v2";

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
      # glibc has C.UTF-8 built in. Same setting as the devenv (item 33).
      LC_CTYPE = "C.UTF-8";

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

    # The desktop app (roadmap item 50, ADR 0053): Electron on the same backend, UI and tools.
    desktop = import ./nix/desktop.nix {
      inherit pkgs nixpkgs sourceOf backend frontend whisperCpp ffmpeg;
    };
  in {
    packages.x86_64-linux = {
      inherit backend frontend desktop;
      # The desktop app as one self-contained executable (nix/desktop-bundle.nix, ADR 0053).
      desktop-bundle = import ./nix/desktop-bundle.nix {inherit pkgs desktop;};

      # Roadmap item 56: the release asset for the bundle's Vulkan-only Mesa on this system, with the
      # pin to paste into nix/desktop-artifacts.nix. The user builds it (the source Mesa is an
      # input, so it must be in the store) and uploads the tar to a `desktop-mesa-<n>` release of
      # this repository; agents never publish. `pin.nix` carries the sha256, the source's out path
      # and its direct references, printed with `cat result/pin.nix`.
      desktop-bundle-mesa-asset = let
        mesa = (import ./nix/desktop-mesa.nix {inherit pkgs;}).source;
        name = "${mesa.name}-${system}.tar.zst";
      in
        pkgs.runCommand "stt-bundle-mesa-asset" {
          __structuredAttrs = true;
          nativeBuildInputs = [pkgs.zstd pkgs.coreutils pkgs.jq];
          exportReferencesGraph.graph = [mesa];
        } ''
          mkdir $out
          tar -cf - --hard-dereference --sort=name --mtime="@$SOURCE_DATE_EPOCH" \
            --owner=0 --group=0 --mode=u+rw,uga+r -C ${mesa} . \
            | zstd -19 -T0 -o $out/${name}
          references=$(
            jq -r --arg self ${mesa} '.graph[] | select(.path == $self) | .references[] | select(. != $self)' \
              $NIX_ATTRS_JSON_FILE
          )
          {
            echo "  mesaVulkan.${system} = {"
            echo "    url = \"https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-mesa-v1/${name}\";"
            echo "    sha256 = \"$(sha256sum $out/${name} | cut -d' ' -f1)\";"
            echo "    storePath = \"${mesa}\";"
            echo "    references = ["
            echo "$references" | sed 's/^/      "/; s/$/"/'
            echo "    ];"
            echo "  };"
          } > $out/pin.nix
          echo "asset: $out/${name}"
          cat $out/pin.nix
        '';

      # Roadmap item 62: the release asset for the audio-only ffmpeg on this system, with the pin to
      # paste into nix/desktop-artifacts.nix. The user builds it and uploads the tar to a
      # `ffmpegAssetTag` release of this repository; agents never publish. `pin.nix` carries the
      # sha256, the source's out path and its direct references, printed with `cat result/pin.nix`.
      desktop-ffmpeg-asset = let
        source = (import ./nix/ffmpeg.nix {inherit pkgs;}).source;
        name = "${source.name}-${system}.tar.zst";
      in
        pkgs.runCommand "stt-ffmpeg-asset" {
          __structuredAttrs = true;
          nativeBuildInputs = [pkgs.zstd pkgs.coreutils pkgs.gnused pkgs.jq];
          exportReferencesGraph.graph = [source];
        } ''
          mkdir $out
          tar -cf - --hard-dereference --sort=name --mtime="@$SOURCE_DATE_EPOCH" --owner=0 --group=0 --mode=u+rw,uga+r -C ${source} . | zstd -19 -T0 -o $out/${name}
          references=$(
            jq -r --arg self ${source} '.graph[] | select(.path == $self) | .references[] | select(. != $self)' $NIX_ATTRS_JSON_FILE
          )
          {
            echo "  ffmpeg.${system} = {"
            echo "    url = \"https://github.com/lulu-berlin/speech-to-text-app/releases/download/${ffmpegAssetTag}/${name}\";"
            echo "    sha256 = \"$(sha256sum $out/${name} | cut -d' ' -f1)\";"
            echo "    storePath = \"${source}\";"
            echo "    references = ["
            if [ -n "$references" ]; then echo "$references" | sed 's/^/      "/; s/$/"/'; fi
            echo "    ];"
            echo "  };"
          } > $out/pin.nix
          echo "asset: $out/${name}"
          cat $out/pin.nix
        '';

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
          # whisper-cli) cannot replace them. The audio-only ffmpeg from the pinned
          # desktop-ffmpeg-v2 asset (roadmap item 62), the branch item 33's Gradle bootstrap
          # syncs to (8.1), not nixpkgs' default ffmpeg (9.0 at the locked rev).
          makeWrapper ${pkgs.jre}/bin/java $out/bin/stt-app \
            --run 'if [ -z "''${STT_DATA_DIR:-}" ]; then export STT_DATA_DIR="''${XDG_DATA_HOME:-$HOME/.local/share}/stt-app"; fi; ${pkgs.coreutils}/bin/mkdir -p "$STT_DATA_DIR"' \
            --set STT_STATIC_DIR "$out/share/stt-app/static" \
            --set-default STT_WHISPER_MODEL ${models.whisper} \
            --set-default STT_SPEAKER_MODEL ${models.speaker} \
            --prefix PATH : ${pkgs.lib.makeBinPath [whisperCpp ffmpeg pkgs.util-linux pkgs.coreutils]} \
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
        pkgs.runCommand "stt-app-docker-root" { } ''
          for icd in ${pkgs.lib.concatStringsSep " " vkIcds}; do test -e "$icd"; done
          mkdir -p $out/bin $out/usr/local/bin $out/etc
          ln -s ${entrypoint} $out/entrypoint.sh
          ln -s ${pkgs.runtimeShell} $out/bin/sh
          ln -s ${pkgs.lib.getExe pkgs.curl} $out/usr/local/bin/curl
          printf 'root:x:0:0:root:/root:/bin/sh\nstt:x:1000:1000:stt:/home/stt:/bin/sh\n' > $out/etc/passwd
          printf 'root:x:0:\nstt:x:1000:\n' > $out/etc/group
          printf 'passwd: files\nshadow: files\ngroup: files\nhosts: files dns\nnetworks: files\nservices: files\n' > $out/etc/nsswitch.conf
        '';
    };

    # `nix run .` starts bin/stt-app (named by meta.mainProgram), on http://localhost:8080.
    apps.x86_64-linux.default = {
      type = "app";
      program = pkgs.lib.getExe self.packages.${system}.default;
      meta.description = "Start the Speech-to-Text app";
    };

    # `nix run .#desktop` opens the desktop app (roadmap item 50).
    apps.x86_64-linux.desktop = {
      type = "app";
      program = pkgs.lib.getExe self.packages.${system}.desktop;
      meta.description = "Open the Speech-to-Text desktop app";
    };

    # `nix run .#desktop-bundle` unpacks and runs the single-file bundle (roadmap item 50).
    apps.x86_64-linux.desktop-bundle = {
      type = "app";
      program = pkgs.lib.getExe self.packages.${system}.desktop-bundle;
      meta.description = "Run the self-contained Speech-to-Text desktop bundle";
    };

    # `nix bundle --bundler .#desktop .#packages.x86_64-linux.desktop` produces the same file: the
    # bundler interface is `bundlers.<system>.<name> = drv: drv'`, and the derivation takes the
    # package to bundle as its argument.
    bundlers.x86_64-linux.desktop = drv:
      import ./nix/desktop-bundle.nix {inherit pkgs; desktop = drv;};

    # ADR 0036: the devenv's nixpkgs input is the flake's locked nixpkgs. `nix flake
    # check` fails when the revs drift; `devenv update` rewrites devenv.lock to
    # devenv.yaml's url. devenv.yaml is written as JSON on purpose, so the check is a
    # plain jq read (JSON is a subset of YAML; devenv's parser accepts it). The check
    # reads only flake.lock and devenv.yaml, so a nixpkgs bump or a devenv.yaml edit
    # (not the parts' sources) rebuilds it.
    checks.x86_64-linux.devenv-pin-sync = pkgs.runCommand "devenv-pin-sync" { } ''
      flakeRev=$(
        ${pkgs.jq}/bin/jq -r '.nodes.nixpkgs.locked.rev' ${./flake.lock}
      )
      devenvRev=$(
        ${pkgs.jq}/bin/jq -r '.inputs.nixpkgs.url | split("/")[-1]' ${./devenv.yaml}
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
      echo "$flakeRev" > $out
    '';

    # Roadmap item 33: the committed versions.json must be exactly what the flake
    # renders (nixpkgs-derived versions + nix/versions.nix + nix/models.nix). The
    # check fails on any drift - a nixpkgs bump, a tag/version change, an edited
    # hash - and the user regenerates with `nix run .#update-versions` (ADR 0010).
    # Reads only the version inputs, so a non-version change does not rerun it.
    checks.x86_64-linux.versions-sync = pkgs.runCommand "versions-sync" { } ''
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
    checks.x86_64-linux.desktop-model-free = let
      modelNames = map (pin: baseNameOf pin.url) (builtins.attrValues models.meta);
      modelPattern = pkgs.lib.concatMapStringsSep "|" pkgs.lib.escapeRegex modelNames;
      closure = pkgs.closureInfo {rootPaths = [desktop];};
    in
      pkgs.runCommand "desktop-model-free" { } ''
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
    checks.x86_64-linux.ffmpeg-no-gstreamer-gtk = let
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

    # Roadmap item 62 (test-list item 2): the audio-only build the pinned artifact is made from — one
    # static binary, no ffprobe or ffplay, and a configure line that names exactly the app's ffmpeg
    # surface: the demuxers of the accepted extensions' containers plus `concat`, the audio decoders
    # the app accepts, the `flac` encoder, the `flac`/`f32le`/`null` muxers, the
    # `silencedetect`/`atrim`/`aresample` filters (with the buffer filters every graph needs) and the
    # `file` protocol — and no video codecs, hwaccels, capture devices or network protocols. Unlike
    # containment alone, the check parses every `--enable-<kind>=` value and compares the exact set
    # (from nix/ffmpeg.nix), so an extra video decoder cannot pass and every added or missing
    # component is named. The format check (item 3) proves the formats; this proves the build.
    checks.x86_64-linux.ffmpeg-audio-only = let
      lib = pkgs.lib;
      config = import ./nix/ffmpeg.nix {inherit pkgs;};
      audioOnly = config.source;
      ffmpeg = "${audioOnly}/bin/ffmpeg";
      # The same lists the build derives its `--enable-<kind>=` flags from, plus the plain flags and
      # the tokens it must not carry; nix/ffmpeg-audio-only.sh compares the built configuration to
      # them exactly.
      expected = pkgs.writeText "ffmpeg-audio-only-expected" ''
        flag --disable-everything
        flag --disable-autodetect
        flag --enable-static
        flag --disable-shared
        flag --enable-ffmpeg
        flag --disable-ffprobe
        flag --disable-ffplay
        forbid --enable-lib
        forbid --enable-network
        forbid --enable-avdevice
        ${lib.concatStrings (map ({kind, components}: "${kind} ${lib.concatStringsSep "," components}\n") config.enable)}
      '';
    in
      pkgs.runCommand "ffmpeg-audio-only" {
        nativeBuildInputs = [pkgs.binutils pkgs.bash pkgs.coreutils pkgs.gnugrep pkgs.gnused];
      } ''
        fail() {
          echo "ffmpeg-audio-only: $1" >&2
          exit 1
        }
        [ -x ${ffmpeg} ] || fail "${ffmpeg} is missing"
        [ "$(ls ${audioOnly}/bin)" = ffmpeg ] || fail "bin/ holds more than ffmpeg: $(ls ${audioOnly}/bin)"
        [ ! -e ${audioOnly}/bin/ffprobe ] || fail "the build ships bin/ffprobe"
        [ ! -e ${audioOnly}/bin/ffplay ] || fail "the build ships bin/ffplay"
        # Static: no library dependency and no program interpreter.
        if ${pkgs.binutils}/bin/readelf -d ${ffmpeg} 2>/dev/null | ${pkgs.gnugrep}/bin/grep -q NEEDED; then
          fail "the binary has dynamic library dependencies"
        fi
        if ${pkgs.binutils}/bin/readelf -l ${ffmpeg} 2>/dev/null | ${pkgs.gnugrep}/bin/grep -q INTERP; then
          fail "the binary has a program interpreter"
        fi
        bash ${./nix/ffmpeg-audio-only.sh} ${ffmpeg} ${expected}
        echo ok > $out
      '';

    # Roadmap item 62 (test-list item 7): the release-asset builder for the audio-only ffmpeg. The
    # user builds `.#desktop-ffmpeg-asset` and uploads the tar to `desktop-ffmpeg-v2`; the pin it
    # prints is what nix/desktop-artifacts.nix records. This check asserts the pin names this build
    # (its store path and every direct reference) and the asset (its sha256 and release URL), so a
    # wrong or stale pin cannot be uploaded unnoticed.
    checks.x86_64-linux.ffmpeg-asset-pin = let
      source = (import ./nix/ffmpeg.nix {inherit pkgs;}).source;
      asset = self.packages.${system}.desktop-ffmpeg-asset;
      name = "${source.name}-${system}.tar.zst";
    in
      pkgs.runCommand "ffmpeg-asset-pin" {
        __structuredAttrs = true;
        nativeBuildInputs = [pkgs.coreutils pkgs.gnugrep pkgs.gnused pkgs.jq];
        exportReferencesGraph.graph = [source];
      } ''
        fail() {
          echo "ffmpeg-asset-pin: $1" >&2
          exit 1
        }
        [ -s ${asset}/${name} ] || fail "the asset ${name} is missing"
        [ -f ${asset}/pin.nix ] || fail "pin.nix is missing"
        ${pkgs.gnugrep}/bin/grep -qF 'ffmpeg.${system} = {' ${asset}/pin.nix ||
          fail "the pin has no ffmpeg.${system} entry"
        ${pkgs.gnugrep}/bin/grep -qF 'releases/download/${ffmpegAssetTag}/${name}' ${asset}/pin.nix ||
          fail "the pin's url does not name ${name} on ${ffmpegAssetTag}"
        ${pkgs.gnugrep}/bin/grep -qF 'storePath = "${source}"' ${asset}/pin.nix ||
          fail "the pin's storePath is not this build's ${source}"
        got=$(sha256sum ${asset}/${name} | cut -d' ' -f1)
        ${pkgs.gnugrep}/bin/grep -qF "sha256 = \"$got\"" ${asset}/pin.nix ||
          fail "the pin's sha256 is not the asset's $got"
        references=$(${pkgs.jq}/bin/jq -r --arg self ${source} \
          '.graph[] | select(.path == $self) | .references[] | select(. != $self)' $NIX_ATTRS_JSON_FILE)
        for reference in $references; do
          ${pkgs.gnugrep}/bin/grep -qF "\"$reference\"" ${asset}/pin.nix ||
            fail "the pin does not list the source's reference $reference"
        done
        listed=$(${pkgs.gnused}/bin/sed -n '/references = \[/,/\]/p' ${asset}/pin.nix | ${pkgs.gnugrep}/bin/grep -c '"/nix/store/' || true)
        expected=$(printf '%s\n' "$references" | ${pkgs.gnugrep}/bin/grep -c '/nix/store/' || true)
        [ "$listed" = "$expected" ] ||
          fail "the pin lists $listed references, but the source has $expected"
        echo ok > $out
      '';

    # Roadmap item 62 (test-list item 1): every x86_64-linux build ships the same audio-only ffmpeg —
    # the desktop's tools (and so the bundle's), the server package's wrapper PATH (and so
    # docker-root's closure) — as the pinned release asset, not a source build. The pin must be
    # registered, the shipped derivation must not be the source build, and the consumers must
    # resolve to the same binary. The devenv shell uses the same import; the empty-store dry-run
    # proves no source ffmpeg is in the shipped graph.
    checks.x86_64-linux.ffmpeg-audio-only-everywhere = let
      lib = pkgs.lib;
      artifacts = import ./nix/desktop-artifacts.nix;
      ffmpegArtifact = import ./nix/ffmpeg.nix {inherit pkgs;};
      ffmpeg = ffmpegArtifact.ffmpeg;
      source = ffmpegArtifact.source;
      server = self.packages.${system}.default;
      dockerRoot = self.packages.${system}.docker-root;
      pinned = artifacts.ffmpeg.${system} or null;
    in
      assert lib.assertMsg (pinned != null)
      "ffmpeg-audio-only-everywhere: nix/desktop-artifacts.nix has no ffmpeg.${system} entry, so the ffmpeg is a source build";
      assert lib.assertMsg (ffmpeg.outPath != source.outPath)
      "ffmpeg-audio-only-everywhere: the shipped ffmpeg is the source build ${source.outPath}, not the prebuilt asset";
      assert lib.assertMsg (desktop.ffmpeg.outPath == ffmpeg.outPath)
      "ffmpeg-audio-only-everywhere: the desktop ships ${desktop.ffmpeg.outPath}, not the prebuilt ${ffmpeg.outPath}";
        pkgs.runCommand "ffmpeg-audio-only-everywhere" {
          nativeBuildInputs = [pkgs.coreutils pkgs.gnugrep];
        } ''
          fail() {
            echo "ffmpeg-audio-only-everywhere: $1" >&2
            exit 1
          }
          desktop=$(readlink -f ${desktop}/share/stt-app/resources/tools/bin/ffmpeg)
          [ "$desktop" = ${ffmpeg}/bin/ffmpeg ] ||
            fail "the desktop's tools ffmpeg is $desktop, not ${ffmpeg}/bin/ffmpeg"
          grep -qF ${ffmpeg}/bin ${server}/bin/stt-app ||
            fail "the server package's wrapper does not name ${ffmpeg}/bin"
          grep -qxF ${ffmpeg} ${pkgs.closureInfo {rootPaths = [dockerRoot];}}/store-paths ||
            fail "docker-root's closure does not hold ${ffmpeg}"
          echo ok > $out
        '';

    # Roadmap item 62 (test-list item 3; the review's two additions): every extension the app accepts
    # decodes to the canonical FLAC through the shipped artifact, and the proof cannot drift from
    # `allowed-extensions.json`. nix/ffmpeg-upload-formats.sh validates each result from its own
    # STREAMINFO — no ffprobe, which the artifact does not ship.
    checks.x86_64-linux.ffmpeg-upload-formats = let
      lib = pkgs.lib;
      ffmpeg = (import ./nix/ffmpeg.nix {inherit pkgs;}).ffmpeg;
      extensions = builtins.fromJSON (builtins.readFile ./backend/src/main/resources/allowed-extensions.json);
      fixtures = ./backend/src/test/resources/upload-formats;
    in
      pkgs.runCommand "ffmpeg-upload-formats" {
        nativeBuildInputs = [pkgs.bash pkgs.coreutils];
      } ''
        bash ${./nix/ffmpeg-upload-formats.sh} ${ffmpeg}/bin/ffmpeg ${fixtures} ${lib.escapeShellArgs extensions}
        echo ok > $out
      '';

    # Roadmap item 62 (test-list item 4): the app's other ffmpeg commands still work with the shipped
    # artifact — f32le PCM decode, silencedetect to the null muxer, the atrim window cut and the V10
    # concat backfill. nix/ffmpeg-app-uses.sh mirrors the app's argument lists; the atrim and concat
    # results are checked from their FLAC STREAMINFO, without ffprobe.
    checks.x86_64-linux.ffmpeg-app-uses = let
      ffmpeg = (import ./nix/ffmpeg.nix {inherit pkgs;}).ffmpeg;
      fixtures = ./backend/src/test/resources/upload-formats;
    in
      pkgs.runCommand "ffmpeg-app-uses" {
        nativeBuildInputs = [pkgs.bash pkgs.coreutils];
      } ''
        bash ${./nix/ffmpeg-app-uses.sh} ${ffmpeg}/bin/ffmpeg ${fixtures}
        echo ok > $out
      '';

    # Roadmap item 62 (test-list item 5): the ffmpeg artifact's integrity gates, like the Mesa ones —
    # a registered asset that is missing or does not match its sha256 fails naming the asset, url and
    # sha256; an asset pinned to another source build fails at evaluation naming both paths; the
    # stray-path scan fails on an unregistered path; the real prebuilt names no old build path, no
    # stray path, and sits in its own runtime closure (its pinned references are empty).
    checks.x86_64-linux.ffmpeg-artifact-integrity = let
      lib = pkgs.lib;
      zeros = "0000000000000000000000000000000000000000000000000000000000000000";
      source = (import ./nix/ffmpeg.nix {inherit pkgs;}).source;
      real = import ./nix/ffmpeg.nix {inherit pkgs;};
      pin = (import ./nix/desktop-artifacts.nix).ffmpeg.${system};
      fixture = pkgs.writeText "ffmpeg-asset-fixture.tar.zst" "not the release asset";
      registered = url: (import ./nix/ffmpeg.nix {
        inherit pkgs;
        artifacts.ffmpeg.${system} = {
          inherit url;
          sha256 = zeros;
          storePath = builtins.unsafeDiscardStringContext source.outPath;
          references = [];
        };
      });
      cases = {
        mismatched = registered "file://${fixture}";
        missing = registered "file://${fixture}.missing";
      };
      expectFailure = label: artifact: let
        asset = artifact.ffmpeg.asset;
      in ''
        if out=$TMPDIR/${label} ${asset.fetch} 2> ${label}.err; then
          fail "the ${label} artifact was accepted"
        fi
        for expected in ${lib.escapeShellArgs [asset.name asset.url asset.sha256]}; do
          grep -qF -- "$expected" ${label}.err || fail "the ${label} artifact's error does not name $expected"
        done
      '';
      stalePin = {
        url = "file://${fixture}";
        sha256 = zeros;
        storePath = "/nix/store/00000000000000000000000000000000-ffmpeg-audio-only-static-x86_64-unknown-linux-musl-8.1.2";
        references = [];
      };
      stale = import ./nix/ffmpeg.nix {
        inherit pkgs;
        artifacts.ffmpeg.${system} = stalePin;
      };
      evaluated = builtins.tryEval stale.ffmpeg.outPath;
      message = stale.pinError or "";
      named = map builtins.unsafeDiscardStringContext [
        (baseNameOf "file://${fixture}")
        "file://${fixture}"
        zeros
        stalePin.storePath
        source.outPath
      ];
      unnamed = lib.filter (expected: !lib.hasInfix expected message) named;
    in
      assert lib.assertMsg (!evaluated.success)
      "ffmpeg-artifact-integrity: a pin made from ${stalePin.storePath} evaluated to ${toString evaluated.value}";
      assert lib.assertMsg (unnamed == [])
      "ffmpeg-artifact-integrity: the stale pin's error does not name ${lib.concatStringsSep ", " unnamed}; it reads: ${message}";
        pkgs.runCommand "ffmpeg-artifact-integrity" {
          nativeBuildInputs = [pkgs.coreutils pkgs.gnugrep];
        } ''
          fail() {
            echo "ffmpeg-artifact-integrity: $1" >&2
            exit 1
          }
          ${lib.concatStrings (lib.mapAttrsToList expectFailure cases)}
          mkdir -p rogue allowed
          printf '/nix/store/00000000000000000000000000000000-rogue\n' > rogue/x
          if stray=$(bash ${./nix/store-path-scan.sh} rogue); then
            fail "the scan accepted the unregistered path"
          fi
          echo "$stray" | grep -qF '00000000000000000000000000000000-rogue' ||
            fail "the scan did not name the unregistered path: $stray"
          ref=/nix/store/11111111111111111111111111111111-reference
          printf '%s\n' "$ref" > allowed/x
          bash ${./nix/store-path-scan.sh} allowed "$ref" || fail "the scan rejected a declared reference"
          if grep -rlaF ${lib.escapeShellArg pin.storePath} ${real.ffmpeg}; then
            fail "the prebuilt still names ${pin.storePath}, the build it was made from"
          fi
          bash ${./nix/store-path-scan.sh} ${real.ffmpeg} || fail "the prebuilt names a stray store path"
          grep -qxF ${real.ffmpeg} ${pkgs.closureInfo {rootPaths = [real.ffmpeg];}}/store-paths ||
            fail "the prebuilt is not in its own runtime closure"
          echo ok > $out
        '';

    # Roadmap item 62 (test-list item 6): the artifact's runtime closure is the static binary alone,
    # holds none of the video libraries the slim build drops, and it is the only ffmpeg in the
    # server package's, docker-root's and the bundle's closures (the whisper-cpp fix). $out is the
    # size report; the official item-61 comparison is `nix path-info -S` run by the user.
    checks.x86_64-linux.ffmpeg-closure-slim = let
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
        '') closures)}
        {
          echo "ffmpeg artifact: $(du -sb ${ffmpeg} | cut -f1) bytes"
          ${lib.concatStrings (lib.mapAttrsToList (name: closure: ''
            echo "${name} closure: $(xargs du -sb < ${closure}/store-paths | awk '{s += $1} END {print s + 0}') bytes"
          '') closures)}
        } > $out
        cat $out
      '';

    # Roadmap item 56: the bundle's Vulkan-only Mesa may come from a prebuilt release asset only on
    # a system that has one registered; any other system builds it from source. Checked for
    # aarch64-linux, which has none: its bundle Mesa must be the source derivation itself.
    # Evaluation only (nixpkgs' cross package set), so nothing for aarch64 is built.
    checks.x86_64-linux.desktop-mesa-unregistered-system = let
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
    checks.x86_64-linux.desktop-mesa-artifact-integrity = let
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
    checks.x86_64-linux.desktop-mesa-stale-pin = let
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
    checks.x86_64-linux.desktop-mesa-prebuilt = let
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

    # Regenerates the committed versions.json from the flake's versions (ADR 0010: the
    # user runs this; writes to the checkout the command is run from).
    apps.x86_64-linux.update-versions = {
      type = "app";
      program = "${pkgs.writeShellScript "update-versions" ''
        exec ${pkgs.jq}/bin/jq -S . ${versionsJson} > versions.json
      ''}";
      meta.description = "Regenerate the committed versions.json from the flake's versions";
    };
  };
}
