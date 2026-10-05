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
  # https://devenv.sh/basics/
  env.GREET = "devenv";

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

  # https://devenv.sh/processes/
  # processes.dev.exec = "${lib.getExe pkgs.watchexec} -n -- ls -la";

  # https://devenv.sh/services/
  # services.postgres.enable = true;

  # https://devenv.sh/scripts/
  scripts.hello.exec = ''
    echo hello from $GREET
  '';

  # https://devenv.sh/basics/
  enterShell = ''
    hello         # Run scripts directly
    git --version # Use packages
  '';

  # https://devenv.sh/tasks/
  # tasks = {
  #   "myproj:setup".exec = "mytool build";
  #   "devenv:enterShell".after = [ "myproj:setup" ];
  # };

  # https://devenv.sh/tests/
  enterTest = ''
    echo "Running tests"
    git --version | grep --color=auto "${pkgs.git.version}"
  '';

  # https://devenv.sh/git-hooks/
  # git-hooks.hooks.shellcheck.enable = true;

  # See full reference at https://devenv.sh/reference/options/
}
