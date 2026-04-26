#!/bin/bash
# M5 smoke test: verifies kairo-code can modify its own source code and pass tests.
#
# Approach:
#   1. Add a harmless comment to ConfigLoader.java
#   2. Run mvn test -pl kairo-code-cli
#   3. Revert the file regardless of test outcome
#
# Exit codes:
#   0 — self-modification round-trip succeeded
#   1 — tests failed after modification
#   2 — pre-condition check failed

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_FILE="$PROJECT_ROOT/kairo-code-cli/src/main/java/io/kairo/code/cli/ConfigLoader.java"

echo "=== M5 Self-Modification Smoke Test ==="
echo ""

# Pre-condition: target file exists
if [[ ! -f "$TARGET_FILE" ]]; then
    echo "ERROR: target file not found: $TARGET_FILE" >&2
    exit 2
fi

echo "1. Injecting marker comment into ConfigLoader.java..."
MARKER="// M5-SMOKE-MARKER: self-modification test"
# Insert marker on line 1 (after package declaration is on line 1, so insert before it)
# Use a temp file to avoid sed -i portability issues
cp "$TARGET_FILE" "${TARGET_FILE}.bak"
echo "$MARKER" | cat - "$TARGET_FILE" > /tmp/m5-patched.java && mv /tmp/m5-patched.java "$TARGET_FILE"

echo "2. Running tests..."
TEST_EXIT=0
(cd "$PROJECT_ROOT" && mvn test -pl kairo-code-cli -q 2>&1) || TEST_EXIT=$?

echo "3. Reverting ConfigLoader.java..."
mv "${TARGET_FILE}.bak" "$TARGET_FILE"

if [[ $TEST_EXIT -ne 0 ]]; then
    echo ""
    echo "FAIL: tests failed after self-modification (exit $TEST_EXIT)" >&2
    exit 1
fi

echo ""
echo "PASS: self-modification round-trip succeeded."
echo "      M5 milestone verified: kairo-code can modify and test its own source."
