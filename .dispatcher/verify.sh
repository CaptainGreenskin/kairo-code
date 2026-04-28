#!/usr/bin/env bash
# Per-project verification command. cli-dispatcher runs this in the worktree root
# after the executor finishes. Exit 0 = pass.
# Override this for your build tool.
#
# PORTABLE TIMEOUT: macOS lacks GNU `timeout` by default. If you want a hard
# wall-clock cap, use the helper below (works on Linux + macOS w/ Homebrew
# coreutils):
#
#     TIMEOUT="$(command -v timeout || command -v gtimeout || true)"
#     if [[ -n "$TIMEOUT" ]]; then exec "$TIMEOUT" 30m mvn -q verify; fi
#     exec mvn -q verify
set -euo pipefail
TIMEOUT="$(command -v timeout || command -v gtimeout || true)"
if [[ -n "$TIMEOUT" ]]; then
  exec "$TIMEOUT" 20m mvn -q verify
fi
exec mvn -q verify
