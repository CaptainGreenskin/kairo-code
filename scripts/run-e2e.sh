#!/bin/bash
# Run the E2E "fix failing test" demo
# Requires: KAIRO_CODE_API_KEY environment variable

set -e

if [ -z "$KAIRO_CODE_API_KEY" ]; then
    echo "Error: KAIRO_CODE_API_KEY not set"
    echo "Usage: KAIRO_CODE_API_KEY=your-key ./scripts/run-e2e.sh"
    exit 1
fi

echo "=== Running Kairo Code E2E: Fix Failing Test ==="
cd "$(dirname "$0")/.."
mvn test -pl kairo-code-examples -Dtest=FixFailingTestE2E
echo "=== E2E Complete ==="
