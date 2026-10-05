{
  pkgs,
  lib,
  config,
  inputs,
  ...
}:

let
  models = import ./nix/models.nix { inherit pkgs; };
in
{
  # https://devenv.sh/packages/
  packages = [ 
    pkgs.git 
    
    # Transcribe CLI
    (pkgs.whisper-cpp.override { vulkanSupport = true; }) # GPU accel on AMD + NVIDIA via Vulkan
    
    # Audio tooling: whisper-cpp already decodes any format via embedded FFmpeg,
    # standalone ffmpeg is handy for trimming/merging audio before transcription
    pkgs.ffmpeg
    
    # Diagnostics: `vulkaninfo --summary` verifies the GPU is visible to Vulkan
    pkgs.vulkan-tools

    pkgs.sqlite

    # Manual API/SSE smoke checks against the backend (`curl -N` for SSE)
    pkgs.curl
    pkgs.jq
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
