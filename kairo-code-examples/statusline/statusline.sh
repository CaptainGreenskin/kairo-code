#!/usr/bin/env bash
# Reference status-line script for kairo-code REPL.
#
# Install: cp statusline.sh ~/.kairo-code/statusline.sh && chmod +x ~/.kairo-code/statusline.sh
# Then in ~/.kairo-code/statusline.json:
#   { "type": "command", "command": "bash ~/.kairo-code/statusline.sh" }
#
# Requires `jq`. Test locally:
#   echo '{"model":{"displayName":"sonnet-4"},"contextWindow":{"usedPercentage":42.3}}' | bash statusline.sh

set -euo pipefail

# Read the JSON state piped from kairo-code on stdin.
JSON=$(cat)

# Safe extraction with jq's // (default) operator — fields the runtime
# couldn't populate are absent and default to literal "?".
MODEL=$(echo "$JSON" | jq -r '.model.displayName // "?"')
USED_PCT=$(echo "$JSON" | jq -r '.contextWindow.usedPercentage // 0')

# Color by usage band (mirror of the built-in TokenStatusLine).
USED_INT=${USED_PCT%.*}
if   [ "$USED_INT" -ge 90 ]; then COLOR="\033[31m"  # red
elif [ "$USED_INT" -ge 80 ]; then COLOR="\033[33m"  # yellow
else                              COLOR="\033[90m"  # gray
fi

printf "%b[%s | ctx %d%%]\033[0m\n" "$COLOR" "$MODEL" "$USED_INT"
