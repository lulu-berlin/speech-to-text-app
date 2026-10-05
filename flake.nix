# Minimal flake building both parts (roadmap item 02, decision 7; ADR 0014).
# Finished in roadmap item 16 (app wrapper, models, nix run .).
# Nix evaluation happens on the user's machine - this file is unverified here.
{
  description = "Speech-to-Text app (backend + frontend build check, roadmap item 02)";

  inputs = {
    # Shared-with-devenv pin question is roadmap item 16's open question (ADR 0014).
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { self, nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};

      backend = pkgs.stdenv.mkDerivation (finalAttrs: {
        pname = "stt-backend";
        version = "0.1.0-SNAPSHOT";
        src = self;

        # Directory sources unpack to `./source/` (stripHash of the store path);
        # nixpkgs cds into `$sourceRoot` after unpackPhase, so it must include the
        # `source/` prefix. Gradle then runs in backend/.
        sourceRoot = "source/backend";

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

        # Do not download a Gradle distribution; use nixpkgs' gradle (ADR 0014).
        gradleFlags = [ "--no-daemon" ];

        # `gradle build` includes ktlintCheck + tests via the check task (ADR 0015).
        gradleBuildTask = "build";
        doCheck = true;

        installPhase = ''
          runHook preInstall
          mkdir -p $out/lib/stt-backend
          install -m 0644 $src/backend/build/libs/*.jar $out/lib/stt-backend/
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
        pnpmDeps = pkgs.fetchPnpmDeps {
          inherit (finalAttrs) pname version src sourceRoot pnpmBuildScript pnpmBuildFlags;
          pnpm = pkgs.pnpm_11;
          fetcherVersion = 4;
          # GENERATION (user): placeholder hash -> run `nix build .#frontend` -> paste the
          # reported "got: sha256-..." value here; repeat until it builds.
          hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        };

        # `pnpm build` runs tsc --noEmit && vite build (ADR 0015 still applies).
        pnpmBuildScript = "build";

        installPhase = ''
          runHook preInstall
          mkdir -p $out
          cp -r $src/frontend/dist/. $out/
          runHook postInstall
        '';
      });
    in
    {
      packages.x86_64-linux = {
        inherit backend frontend;

        # `nix build .` builds both parts (item 02 acceptance criterion).
        default = pkgs.symlinkJoin {
          name = "stt-app-build";
          paths = [ backend frontend ];
        };
      };
    };
}