{
  pkgs,
  config,
  ...
}:

let
  models = import ./nix/models.nix { inherit pkgs; };
  testFixtures = import ./nix/test-fixtures.nix { inherit pkgs; };
in
{
  # https://devenv.sh/packages/
  packages = [
    pkgs.git

    # Transcribe CLI, the same pinned 1.8.4 Vulkan build the package ships (nix/whisper-cpp.nix)
    (import ./nix/whisper-cpp.nix { inherit pkgs; })
    
    # Audio tooling: whisper-cpp's bundled miniaudio decodes the app's FLAC chunks
    # (from the 1.8.4 pin the WHISPER_FFMPEG build flag is a no-op — see
    # nix/whisper-cpp.nix); standalone ffmpeg is handy for trimming/merging audio
    # before transcription. ffmpeg_8, like the package (no drift; item 33's Gradle
    # bootstrap syncs 8.1 too).
    pkgs.ffmpeg_8
    
    # Diagnostics: `vulkaninfo --summary` verifies the GPU is visible to Vulkan
    pkgs.vulkan-tools

    pkgs.sqlite

    # Manual API/SSE smoke checks against the backend (`curl -N` for SSE)
    pkgs.curl
    pkgs.jq

    # Frontend package manager (ADR 0012): same major as the flake's pnpm_11,
    # because the lockfile format is tied to the pnpm major.
    pkgs.pnpm_11

    # Process runner (roadmap item 05, ADR 0020): `setsid` puts every spawned
    # command in its own process group so SIGSTOP/SIGCONT/TERM/KILL reach the
    # whole tree (negative pid), not just the direct child.
    pkgs.util-linux

    # E2E browser runtime (roadmap item 21): Chromium for `pnpm --dir e2e test`,
    # found through PLAYWRIGHT_BROWSERS_PATH below. e2e/'s @playwright/test stays
    # pinned to the playwright version THIS devenv provides (devenv's pkgs come from
    # devenv.yaml's devenv-nixpkgs input — currently playwright 1.61.1, browser
    # revisions 1228 — NOT from the flake's nixpkgs): the npm package and the farm
    # must agree on the browser revisions. Bump both together. Dev only — the flake's
    # build is untouched (ADR 0010).
    pkgs.playwright-driver
  ];

  # https://devenv.sh/languages/
  # Kotlin brings JDK 21 + Gradle. The ONNX Runtime native lib bundled in the
  # Maven artifact loads under this JDK without LD_LIBRARY_PATH (verified 2026-10-05).
  languages.kotlin.enable = true;
  languages.typescript.enable = true;
  # Node + npm for the Vite/React frontend
  languages.javascript.enable = true;
  languages.javascript.npm.enable = true;

  # Models for the app and integration tests (pinned in nix/models.nix)
  env.STT_WHISPER_MODEL = "${models.whisper}";
  env.STT_SPEAKER_MODEL = "${models.speaker}";

  # Integration-test fixtures of roadmap item 07 (test-only, TEST_ prefix: STT_* is read only by Config)
  env.TEST_WHISPER_TINY_EN_MODEL = "${models.whisperTinyEn}";
  env.TEST_JFK_WAV = "${testFixtures.jfkWav}";

  # E2E browsers (roadmap item 21): the suite resolves its browser from the store
  # (ADR 0010) instead of downloading one ad hoc; only Chromium is in the farm.
  env.PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers.override { withFirefox = false; withWebkit = false; withFfmpeg = false; }}";

  # Roadmap item 34 (dev mode): dev data (stt.db, uploads) lives in devenv's state dir, a
  # fixed, git-ignored location (.gitignore covers .devenv*), so the queue and history
  # survive `devenv up` restarts; the packaged app sets its own STT_DATA_DIR (flake.nix).
  env.STT_DATA_DIR = "${config.devenv.state}/stt";

  # https://devenv.sh/processes/
  # Roadmap item 34 (dev mode): `devenv up` runs the whole app for development — the Vite
  # dev server with hot module reload and the Ktor backend. No browser is opened; the
  # frontend process prints the Vite URL. Backend restart-on-change is a later test cycle
  # (continuous build + restart), not Ktor auto-reload.
  processes = {
    frontend = {
      # `pnpm install` makes a fresh worktree self-sufficient; up to date it takes a second.
      # The echo is the stop hint (item 34, discovered): the devenv TUI itself gives none.
      # The `&&` keeps the original failure semantics: a failed install stops the dev server.
      exec = ''
        echo 'Stop: press Ctrl-C then q (confirm) to quit devenv and stop both processes (or run `devenv down`)'
        pnpm install && pnpm dev
      '';
      cwd = "frontend";
    };
    backend = {
      # Dev runner (roadmap item 34): `gradle installDist` then start the installed
      # server; rebuilds and restarts it when sources change, keeping the last working
      # server on a build error. Not `gradle run`: the server must not run a second
      # Gradle invocation alongside the continuous build.
      exec = "bash dev-run.sh";
      cwd = "backend";
    };
  };

  # https://devenv.sh/tests/
  # `devenv test`: test list of roadmap item 01 (dev environment).
  # Expected hashes are written out here on purpose, independent of nix/models.nix.
  enterTest = ''
    ok() { echo "ok: $1"; }
    fail() { echo "FAILED: $1" >&2; exit 1; }

    { node --version && npm --version; } >/dev/null 2>&1 \
      && ok "node and npm" || fail "node and npm"

    { java -version 2>&1 | grep -q 'version "21\.' && kotlin -version && gradle --version; } >/dev/null 2>&1 \
      && ok "JDK 21 java, kotlin, gradle" || fail "JDK 21 java, kotlin, gradle"

    { whisper-cli --help && ffmpeg -version && sqlite3 --version && curl --version && jq --version; } >/dev/null 2>&1 \
      && ok "whisper-cli, ffmpeg, sqlite3, curl, jq" || fail "whisper-cli, ffmpeg, sqlite3, curl, jq"

    echo "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2  $STT_WHISPER_MODEL" | sha256sum -c --status - \
      && ok "whisper model sha256 (ADR 0008)" || fail "whisper model sha256 (ADR 0008)"

    echo "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b  $STT_SPEAKER_MODEL" | sha256sum -c --status - \
      && ok "speaker model sha256 (ADR 0003)" || fail "speaker model sha256 (ADR 0003)"
  '';
}
