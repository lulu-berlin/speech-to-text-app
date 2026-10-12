#!/usr/bin/env bash
# Dev-mode backend runner (roadmap item 34): build the installed distribution and run
# its server, rebuilding and restarting it when backend sources change. On a build
# failure the error stays visible here and the last working server keeps running
# (item 4). Not `gradle run`: a running server must not hold a second Gradle build
# alongside `installDist`.
set -u

# The repository root (this script lives in backend/); the devenv process runs with
# cwd=backend, but direct invocations from elsewhere work too.
cd "$(dirname "$0")" || exit 1

DIST_BIN="build/install/stt-backend/bin/stt-backend"
MARKER="build/.stt-dev-marker"
SERVER_PID=""

# Watched paths, newer-than the marker (touched on every detected change). Sources and
# the backend's build files; build/ itself is ignored so our own output never retriggers.
WATCH=(src build.gradle.kts settings.gradle.kts)

# Shutdown grace: poll the JVM every quarter second, KILL it after 5 seconds.
SHUTDOWN_STEPS=20
SHUTDOWN_STEP_S=0.25

start_server() {
  if [ -x "$DIST_BIN" ]; then
    echo "[dev-run] starting $DIST_BIN"
    "$DIST_BIN" &
    SERVER_PID=$!
  else
    echo "[dev-run] $DIST_BIN missing — build before starting" >&2
  fi
}

stop_server() {
  [ -n "$SERVER_PID" ] || return 0
  # TERM the JVM: Ktor shuts down gracefully; engine subprocesses (whisper-cli) either
  # finish or die on their broken stdout pipe. KILL after the grace period.
  kill -TERM "$SERVER_PID" 2>/dev/null || true
  for _ in $(seq 1 "$SHUTDOWN_STEPS"); do
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep "$SHUTDOWN_STEP_S"
  done
  kill -KILL "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
}

rebuild_and_restart() {
  echo "[dev-run] change detected; rebuilding (installDist)"
  # ./gradlew dev sets DEV_RUN_GRADLE to the repo's wrapper; the devenv leaves it
  # unset, so `gradle` comes from PATH (item 33).
  if ${DEV_RUN_GRADLE:-gradle} installDist --console=plain; then
    stop_server
    start_server
  else
    echo "[dev-run] build failed — keeping the last working server" >&2
  fi
}

stop() {
  echo "[dev-run] stopping"
  stop_server
  exit 0
}
trap stop TERM INT

mkdir -p build
touch "$MARKER"
rebuild_and_restart

while true; do
  if find "${WATCH[@]}" -newer "$MARKER" -print -quit 2>/dev/null | grep -q .; then
    touch "$MARKER"
    rebuild_and_restart
  fi
  sleep 1
done