#!/usr/bin/env bash
# Dev mode for the Gradle bootstrap (roadmap item 33), mirroring `devenv up` (item 34):
# the Vite dev server with hot module reload and the backend through backend/dev-run.sh
# (gradle installDist, restart on source change, keep the last working server on errors).
# The Gradle `dev` task starts this script with the managed tools' bin/ first on PATH,
# JAVA_HOME set to the pinned JDK 21 and STT_DATA_DIR/STT_* defaults, then waits here;
# stopping the build (Ctrl-C) kills both.
#
# Two shells scripts coexist on purpose: this one starts *both* halves, while
# backend/dev-run.sh runs only the backend restart loop.
set -u

is_running() {
  kill -0 "$1" 2>/dev/null
}

# Item 34's runner (backend/dev-run.sh). DEV_RUN_GRADLE: the dev task points at the
# repo's ./gradlew; other callers (devenv) get gradle from PATH. The runner is stopped
# with a direct TERM, not a kill of its process group: every caller runs this script as
# a session leader, so its TERM trap reaches the halves and the group kill would have no
# member to signal it.
(
  cd backend || exit 1
  trap '[ -n "${DEV_RUN_PID:-}" ] && kill -TERM "$DEV_RUN_PID"' TERM INT
  DEV_RUN_GRADLE=${DEV_RUN_GRADLE:-gradle} bash dev-run.sh &
  DEV_RUN_PID=$!
  wait "$DEV_RUN_PID"
) &
bpid=$!

trap '[ -n "${fpid:-}" ] && { kill -TERM "$fpid" 2>/dev/null; wait "$fpid" 2>/dev/null; }; [ -n "${bpid:-}" ] && { kill -TERM "$bpid" 2>/dev/null; wait "$bpid" 2>/dev/null; }' TERM INT

# The frontend, like the devenv's `processes.frontend`: ensure deps, then the Vite dev
# server (HMR). `--frozen-lockfile` matches the Nix build's install.
(
  cd frontend || exit 1
  pnpm install --frozen-lockfile && exec pnpm dev
) &
fpid=$!

# Wait until either half exits, then stop the surviving one and reap both.
while is_running "$fpid" && is_running "$bpid"; do sleep 1; done
[ -n "${fpid:-}" ] && kill -TERM "$fpid" 2>/dev/null
[ -n "${bpid:-}" ] && kill -TERM "$bpid" 2>/dev/null
wait "$fpid" 2>/dev/null
wait "$bpid" 2>/dev/null
exit 0
