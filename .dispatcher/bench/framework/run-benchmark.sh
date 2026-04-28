#!/usr/bin/env bash
# run-benchmark.sh — run a benchmark level with a given executor, auto-score, write report.
# Usage: run-benchmark.sh --executor <kairo-code|qodercli> --level <l1|l2|l3> [--quality <0-15>]
# Env:   KAIRO_CODE_API_KEY, KAIRO_CODE_BASE_URL, KAIRO_CODE_CHAT_PATH, KAIRO_CODE_MODEL

set -euo pipefail

BENCH_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS_DIR="$BENCH_DIR/results"
FRAMEWORK_DIR="$BENCH_DIR/framework"

EXECUTOR=""
LEVEL=""
QUALITY_SCORE=10  # default human-eval quality score

usage() { echo "Usage: $0 --executor <kairo-code|qodercli> --level <l1|l2|l3> [--quality 0-15]"; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --executor) EXECUTOR="$2"; shift 2 ;;
    --level)    LEVEL="$2";    shift 2 ;;
    --quality)  QUALITY_SCORE="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -z "$EXECUTOR" || -z "$LEVEL" ]] && usage

# ── resolve fixture + task ───────────────────────────────────────────────────
case "$LEVEL" in
  l1)
    FIXTURE="$BENCH_DIR/fixtures/l1-bug-fix"
    TASK="$BENCH_DIR/tasks/fix-all.md"
    TOTAL_TESTS=18
    ;;
  l2)
    FIXTURE="$BENCH_DIR/fixtures/l2-event-dispatcher"
    TASK="$BENCH_DIR/tasks/l2-implement.md"
    TOTAL_TESTS=17
    ;;
  l3)
    FIXTURE="$BENCH_DIR/fixtures/l3-self-improvement"
    TASK="$BENCH_DIR/tasks/l3-implement.md"
    TOTAL_TESTS=0  # variable — read from mvn output
    ;;
  *) echo "Unknown level: $LEVEL"; exit 1 ;;
esac

# ── legacy l1 path alias ─────────────────────────────────────────────────────
if [[ "$LEVEL" == "l1" && ! -d "$FIXTURE" ]]; then
  FIXTURE="$BENCH_DIR/fixtures"  # original l1 fixture path
  TASK="$BENCH_DIR/tasks/fix-all.md"
fi

LOG_FILE="/tmp/bench-${EXECUTOR}-${LEVEL}-$(date +%s).log"
TS="$(date +%Y%m%d_%H%M%S)"
REPORT="$RESULTS_DIR/benchmark_${TS}_${EXECUTOR}_${LEVEL}.md"

echo "╔══════════════════════════════════════════════════════╗"
echo "  Benchmark: level=$LEVEL  executor=$EXECUTOR"
echo "  Fixture:   $FIXTURE"
echo "  Log:       $LOG_FILE"
echo "╚══════════════════════════════════════════════════════╝"

# ── pre-run: verify fixture is in skeleton/buggy state ───────────────────────
echo "[pre] verifying fixture starts in broken state..."
PRE_RESULT="$(cd "$FIXTURE" && mvn test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -3 || true)"
if echo "$PRE_RESULT" | grep -q "BUILD SUCCESS"; then
  echo "WARNING: fixture is already passing — restore skeleton first"
  echo "  git checkout $FIXTURE"
  exit 1
fi
echo "[pre] confirmed broken: $PRE_RESULT"

# ── run executor ─────────────────────────────────────────────────────────────
START_TS="$(date +%s)"

case "$EXECUTOR" in
  kairo-code)
    JAR="$(ls "$BENCH_DIR"/../../kairo-code-cli/target/kairo-code-cli-*.jar 2>/dev/null | head -1)"
    [[ -z "$JAR" ]] && { echo "ERROR: kairo-code JAR not found. Run: mvn package -pl kairo-code-cli -DskipTests"; exit 1; }
    KAIRO_CODE_API_KEY="${KAIRO_CODE_API_KEY:-}"
    KAIRO_CODE_BASE_URL="${KAIRO_CODE_BASE_URL:-https://open.bigmodel.cn/api/coding/paas/v4}"
    KAIRO_CODE_CHAT_PATH="${KAIRO_CODE_CHAT_PATH:-/chat/completions}"
    KAIRO_CODE_MODEL="${KAIRO_CODE_MODEL:-GLM-5.1}"
    KAIRO_CODE_API_KEY="$KAIRO_CODE_API_KEY" \
    KAIRO_CODE_BASE_URL="$KAIRO_CODE_BASE_URL" \
    KAIRO_CODE_CHAT_PATH="$KAIRO_CODE_CHAT_PATH" \
    KAIRO_CODE_MODEL="$KAIRO_CODE_MODEL" \
    java -jar "$JAR" \
      --working-dir "$FIXTURE" \
      --task-file "$TASK" \
      --verbose \
      2>&1 | tee "$LOG_FILE"
    ;;
  qodercli)
    qodercli \
      --workspace "$FIXTURE" \
      --print \
      --model qwork-ultimate \
      --yolo \
      --output-format text \
      < "$TASK" \
      2>&1 | tee "$LOG_FILE"
    ;;
  *) echo "Unknown executor: $EXECUTOR"; exit 1 ;;
esac

END_TS="$(date +%s)"
DURATION=$(( END_TS - START_TS ))

# ── post-run: collect results ────────────────────────────────────────────────
echo "[post] running mvn test..."
MVN_OUT="$(cd "$FIXTURE" && mvn test 2>&1)"
PASSED="$(echo "$MVN_OUT" | grep -oE 'Tests run: [0-9]+' | tail -1 | grep -oE '[0-9]+'  || echo 0)"
FAILURES="$(echo "$MVN_OUT" | grep -oE 'Failures: [0-9]+' | tail -1 | grep -oE '[0-9]+' || echo 0)"
ERRORS="$(echo "$MVN_OUT"   | grep -oE 'Errors: [0-9]+'   | tail -1 | grep -oE '[0-9]+' || echo 0)"
PASSED=$(( PASSED - FAILURES - ERRORS ))
[[ "$TOTAL_TESTS" -eq 0 ]] && TOTAL_TESTS="$(echo "$MVN_OUT" | grep -oE 'Tests run: [0-9]+' | tail -1 | grep -oE '[0-9]+' || echo 1)"
BUILD_STATUS="$(echo "$MVN_OUT" | grep -E 'BUILD (SUCCESS|FAILURE)' | tail -1 | grep -oE 'SUCCESS|FAILURE' || echo FAILURE)"

# ── scoring ──────────────────────────────────────────────────────────────────
TEST_SCORE=$(( (PASSED * 35) / TOTAL_TESTS ))

# edit precision: check log for old_string errors
EDIT_ERRORS="$(grep -c 'old_string\|not found\|no match\|EditError' "$LOG_FILE" 2>/dev/null || echo 0)"
if   [[ "$EDIT_ERRORS" -eq 0 ]]; then PRECISION_SCORE=20
elif [[ "$EDIT_ERRORS" -le 2 ]]; then PRECISION_SCORE=12
else PRECISION_SCORE=5
fi

# autonomous verify: did agent run mvn test in log?
if grep -qiE 'mvn test|BUILD (SUCCESS|FAILURE)' "$LOG_FILE" 2>/dev/null; then
  VERIFY_SCORE=20
else
  VERIFY_SCORE=0
fi

# efficiency: count tool calls
TOOL_CALLS="$(grep -cE '^\[STEP [0-9]+\]|tool_call|ToolCall' "$LOG_FILE" 2>/dev/null || echo 0)"
FILES_TOUCHED=3  # approximate
if [[ "$FILES_TOUCHED" -gt 0 ]]; then
  CALLS_PER_FILE=$(( TOOL_CALLS / FILES_TOUCHED ))
else
  CALLS_PER_FILE=$TOOL_CALLS
fi
if   [[ "$CALLS_PER_FILE" -le 5  ]]; then EFF_SCORE=10
elif [[ "$CALLS_PER_FILE" -le 10 ]]; then EFF_SCORE=7
elif [[ "$CALLS_PER_FILE" -le 20 ]]; then EFF_SCORE=4
else EFF_SCORE=1
fi

TOTAL_SCORE=$(( TEST_SCORE + PRECISION_SCORE + VERIFY_SCORE + QUALITY_SCORE + EFF_SCORE ))

# ── write report ─────────────────────────────────────────────────────────────
cat > "$REPORT" <<EOF
# Benchmark Report — ${EXECUTOR} ${LEVEL}

**Timestamp:** ${TS}
**Executor:** ${EXECUTOR}
**Level:** ${LEVEL}
**Duration:** ${DURATION}s

## Results

| Tests | Passed | Failed+Error | Build |
|-------|--------|--------------|-------|
| ${TOTAL_TESTS} | ${PASSED} | $(( TOTAL_TESTS - PASSED )) | ${BUILD_STATUS} |

## Scoring (100 pts)

| Axis | Score | Max | Notes |
|------|-------|-----|-------|
| Test Pass Rate | ${TEST_SCORE} | 35 | ${PASSED}/${TOTAL_TESTS} |
| Edit Precision | ${PRECISION_SCORE} | 20 | edit errors in log: ${EDIT_ERRORS} |
| Autonomous Verify | ${VERIFY_SCORE} | 20 | mvn test in log: $(grep -cE 'mvn test' "$LOG_FILE" 2>/dev/null || echo 0)x |
| Code Quality | ${QUALITY_SCORE} | 15 | human eval |
| Efficiency | ${EFF_SCORE} | 10 | ~${TOOL_CALLS} tool calls |
| **TOTAL** | **${TOTAL_SCORE}** | **100** | |

## Log

\`\`\`
$(tail -30 "$LOG_FILE")
\`\`\`

## Raw Log

Full log: \`${LOG_FILE}\`
EOF

echo ""
echo "══════════════════════════════════════════════════"
echo "  RESULT: ${PASSED}/${TOTAL_TESTS} tests  SCORE: ${TOTAL_SCORE}/100"
echo "  Report: $REPORT"
echo "══════════════════════════════════════════════════"

# ── restore fixture ───────────────────────────────────────────────────────────
KAIRO_REPO="$(cd "$BENCH_DIR/../.." && pwd)"
echo "[restore] git checkout fixture..."
git -C "$KAIRO_REPO" checkout ".dispatcher/bench/fixtures/$(basename "$FIXTURE")/" 2>/dev/null \
  || echo "[restore] WARNING: could not restore fixture via git — restore manually"
echo "[restore] done"
