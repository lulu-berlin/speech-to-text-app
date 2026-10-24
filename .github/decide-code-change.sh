#!/usr/bin/env bash
# Decides whether a CI run builds code (roadmap item 80) and prints `code=true` or `code=false` in
# the `key=value` form the `changes` job appends to $GITHUB_OUTPUT. The workflow passes its context
# in the environment:
#
#   EVENT       github.event_name
#   FORCE_FULL  a pull request's `ci:full` label: `true` forces every job to run
#   BASE_SHA / HEAD_SHA  the pull request's base and head commits
#
# Only a pull request is filtered. Every push, schedule and dispatch run builds code, and so does a
# pull request carrying the label. An empty or unreadable diff reports `code=true`: a comparison
# that told us nothing must never be the reason a job is skipped.
set -euo pipefail

if [ "${EVENT:-}" != pull_request ] || [ "${FORCE_FULL:-false}" = true ]; then
  echo 'code=true'
  exit 0
fi

if ! diff=$(git diff --name-only "${BASE_SHA:-}" "${HEAD_SHA:-}"); then
  echo 'code=true'
  exit 0
fi

printf '%s\n' "$diff" | bash "$(dirname "$0")/classify-change.sh"
