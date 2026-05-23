# Task prompt templates

Real-world templates that drive measurable agent behavior. Promoted from
`kairo-code-eval` after running SWE-bench Verified across multiple model
configurations and watching pass-rate / patch-yield change with each
prompt revision.

## Files

| File | Use when | What it does |
|---|---|---|
| `swe-bench-optimized.md` | SWE-bench / Defects4J / bug-fix benchmarks | Hard-rules the agent to "stop reading after 5 files" and "make the smallest fix" — surfaces patches even on tight token budgets. |
| `swe-bench-v2.md` | Same, but with looser exploration budget | More balanced prompt — keeps the read-then-fix discipline but lets the agent run a few extra grep cycles. |

## Substitution variables

All templates use `{{var}}` placeholders consumed by the CLI's `--task-file`
flow or the SDK's `KairoCodeSession.task(...)`:

| Variable | Source |
|---|---|
| `{{problem_statement}}` | Bug report body |
| `{{repo}}` | `org/repo` slug |
| `{{base_commit}}` | Git SHA the agent starts from |
| `{{hints_section}}` | Optional pre-formatted hint markdown; leave empty if none |

## Why these specific templates work

The two templates emerged from ~10 versioned eval runs on SWE-bench Verified.
The instances kairo-code's default behavior failed on shared a pattern:
the model would spend its iteration budget reading 8-15 files instead of
writing a patch. The "STOP READING AFTER 5 FILES" rule shifted patch-yield
from ~0 to ~5 instances out of 8 in one revision (see
`kairo-code-eval/EVAL_OPTIMIZATION.md` for the receipts).

The optimized template trades verbose justification for behavioral
guardrails. Don't use it for open-ended exploratory tasks — it'll under-read.

## Usage from CLI

```bash
java -jar kairo-code-cli-0.2.0.jar \
  --working-dir /path/to/repo \
  --task-file <(envsubst < templates/swe-bench-optimized.md) \
  --provider glm --model glm-5.1
```

## Usage from kairo-code-eval

```bash
python -m kairo_eval run --agent kairo-code --prompt swe-bench-optimized
```

The eval CLI auto-resolves `swe-bench-*` names against its local
`templates/` first; you can drop a copy in the eval repo to override.
