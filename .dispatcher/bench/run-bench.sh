#!/usr/bin/env bash
# Cross-executor benchmark: kairo-code vs qodercli
# Runs both agents on identical bug-fix tasks and compares results.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="$SCRIPT_DIR/fixtures"
TASK_FILE="$SCRIPT_DIR/tasks/fix-all.md"
RESULTS="$SCRIPT_DIR/results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# ---- Config ----
KAIRO_CODE_JAR="${KAIRO_CODE_JAR:-}"
QODERCLI="${QODERCLI:-qodercli}"
TIMEOUT="${BENCH_TIMEOUT:-300}"

# API key
API_KEY="${KAIRO_CODE_API_KEY:-}"
if [[ -z "$API_KEY" ]]; then
    echo "ERROR: KAIRO_CODE_API_KEY must be set for kairo-code" >&2
    exit 1
fi

# Portable timeout helper (macOS lacks GNU timeout)
TIMEOUT_CMD="$(command -v timeout || command -v gtimeout || true)"
run_with_timeout() {
    local seconds="$1"; shift
    if [[ -n "$TIMEOUT_CMD" ]]; then
        "$TIMEOUT_CMD" "$seconds" "$@"
    else
        "$@"
    fi
}

# ---- Resolve kairo-code JAR ----
if [[ -z "$KAIRO_CODE_JAR" ]]; then
    REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
    KAIRO_CODE_JAR="$REPO_ROOT/kairo-code-cli/target/kairo-code-cli-0.2.0-SNAPSHOT.jar"
fi
if [[ ! -f "$KAIRO_CODE_JAR" ]]; then
    echo "ERROR: kairo-code JAR not found at $KAIRO_CODE_JAR" >&2
    echo "Build it first: mvn package -pl kairo-code-cli -am -DskipTests" >&2
    exit 1
fi

# ---- Prepare working directories ----
WORK_ROOT=$(mktemp -d /tmp/kairo-bench.XXXXXX)
KAIRO_WORK="$WORK_ROOT/kairo-code"
QODER_WORK="$WORK_ROOT/qodercli"

mkdir -p "$KAIRO_WORK" "$QODER_WORK"

# Copy fixture to both working directories
cp -R "$FIXTURES"/* "$KAIRO_WORK"/
cp -R "$FIXTURES"/* "$QODER_WORK"/

echo "=== Cross-Executor Benchmark ==="
echo "Timestamp: $TIMESTAMP"
echo "Working dir: $WORK_ROOT"
echo "kairo-code work: $KAIRO_WORK"
echo "qodercli work: $QODER_WORK"
echo ""

# ---- Verify fixture fails before fixes ----
echo "--- Pre-run verification (should fail) ---"
pre_result=0
(cd "$KAIRO_WORK" && mvn test -q) || pre_result=$?
if [[ $pre_result -eq 0 ]]; then
    echo "ERROR: Tests pass before agent run. Fixture is broken." >&2
    rm -rf "$WORK_ROOT"
    exit 1
fi
echo "Fixture correctly fails (exit code: $pre_result)"
echo ""

# ---- Run kairo-code ----
echo "=== Running kairo-code ==="
kairo_start=$(date +%s)
kairo_exit=0
kairo_output="$RESULTS/kairo-code_${TIMESTAMP}.log"

run_with_timeout "$TIMEOUT" \
    java -jar "$KAIRO_CODE_JAR" \
        --task-file "$TASK_FILE" \
        --api-key "$API_KEY" \
        --model gpt-4o \
        --max-iterations 50 \
        --verbose \
        --show-usage \
        > "$kairo_output" 2>&1 || kairo_exit=$?

kairo_end=$(date +%s)
kairo_duration=$((kairo_end - kairo_start))
echo "kairo-code finished in ${kairo_duration}s (exit: $kairo_exit)"

# Parse kairo-code metrics from log
kairo_steps=$(grep -c '\[STEP' "$kairo_output" 2>/dev/null || echo "0")
kairo_tokens=$(grep -oP 'total_tokens[=: ]+\K[0-9]+' "$kairo_output" 2>/dev/null | tail -1 || echo "N/A")
kairo_cost=$(grep -oP 'est_cost_usd[=~: ]+\K[0-9.]+' "$kairo_output" 2>/dev/null | tail -1 || echo "N/A")

# ---- Run qodercli ----
echo "=== Running qodercli ==="
qoder_start=$(date +%s)
qoder_exit=0
qoder_output="$RESULTS/qodercli_${TIMESTAMP}.log"

TASK_CONTENT=$(cat "$TASK_FILE")

run_with_timeout "$TIMEOUT" \
    "$QODERCLI" \
        -p "$TASK_CONTENT" \
        --cwd "$QODER_WORK" \
        --dangerously-skip-permissions \
        --print \
        > "$qoder_output" 2>&1 || qoder_exit=$?

qoder_end=$(date +%s)
qoder_duration=$((qoder_end - qoder_start))
echo "qodercli finished in ${qoder_duration}s (exit: $qoder_exit)"

# Parse qodercli metrics
qoder_steps=$(grep -cP '\[tool|Tool call|Calling tool' "$qoder_output" 2>/dev/null || echo "0")

# ---- Evaluate results ----
echo ""
echo "=== Evaluating results ==="

kairo_test_exit=0
(cd "$KAIRO_WORK" && mvn test -q 2>&1) || kairo_test_exit=$?
kairo_tests_output=$(cd "$KAIRO_WORK" && mvn test 2>&1 || true)
kairo_tests_total=$(echo "$kairo_tests_output" | grep -oP 'Tests run: \K[0-9]+' | tail -1 || echo "0")
if [[ $kairo_test_exit -eq 0 ]]; then
    kairo_tests_passed="$kairo_tests_total"
    kairo_tests_failed=0
else
    kairo_tests_failed=$(echo "$kairo_tests_output" | grep -oP 'Failures: \K[0-9]+' | tail -1 || echo "0")
    kairo_tests_passed=$((kairo_tests_total - kairo_tests_failed))
fi

qoder_test_exit=0
(cd "$QODER_WORK" && mvn test -q 2>&1) || qoder_test_exit=$?
qoder_tests_output=$(cd "$QODER_WORK" && mvn test 2>&1 || true)
qoder_tests_total=$(echo "$qoder_tests_output" | grep -oP 'Tests run: \K[0-9]+' | tail -1 || echo "0")
if [[ $qoder_test_exit -eq 0 ]]; then
    qoder_tests_passed="$qoder_tests_total"
    qoder_tests_failed=0
else
    qoder_tests_failed=$(echo "$qoder_tests_output" | grep -oP 'Failures: \K[0-9]+' | tail -1 || echo "0")
    qoder_tests_passed=$((qoder_tests_total - qoder_tests_failed))
fi

# ---- Generate report ----
report_file="$RESULTS/benchmark_${TIMESTAMP}.md"

cat > "$report_file" << REPORT_EOF
# Cross-Executor Benchmark Report

**Timestamp:** $TIMESTAMP
**Task:** Fix 3 Java bugs (RateLimiter, Cache, StringUtils)
**Total tests:** 18

## Summary

| Metric | kairo-code | qodercli |
|--------|-----------|----------|
| Exit code | $kairo_exit | $qoder_exit |
| Duration | ${kairo_duration}s | ${qoder_duration}s |
| Tests passed | $kairo_tests_passed/$kairo_tests_total | $qoder_tests_passed/$qoder_tests_total |
| Tool calls / steps | $kairo_steps | $qoder_steps |
| Total tokens | $kairo_tokens | N/A |
| Est. cost (USD) | $kairo_cost | N/A |

## Verdict

| Criteria | Winner |
|----------|--------|
| Tests fixed | _fill manually_ |
| Speed | _fill manually_ |
| Token efficiency | _fill manually_ |

## Logs

- **kairo-code log:** \`$kairo_output\`
- **qodercli log:** \`$qoder_output\`

## Notes

- kairo-code runs with \`--verbose --show-usage\` for metrics on stderr.
- qodercli runs with \`--dangerously-skip-permissions\` (headless).
- Timeout per agent: ${TIMEOUT}s.
REPORT_EOF

echo ""
echo "=== Results ==="
cat "$report_file"

# ---- Cleanup ----
echo ""
echo "Cleaning up temp directories: $WORK_ROOT"
rm -rf "$WORK_ROOT"

echo "Report saved to: $report_file"
echo "Logs: $kairo_output, $qoder_output"

exit 0
