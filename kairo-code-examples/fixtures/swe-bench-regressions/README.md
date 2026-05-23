# SWE-bench regression fixtures

Tracked-failure registry: SWE-bench Verified instances where `kairo-code` is
currently known to produce **no patch**. Each entry is a target — when we
land a fix (model change, hook upgrade, prompt tweak) we expect the listed
instance to flip to "produces a non-empty patch" on the next eval run.

This is NOT a Java test suite — running these against a real LLM takes
hours per instance and costs money. It's a fact ledger that lets us:

- Notice regressions: a fix elsewhere shouldn't take a previously-attempted
  instance back to "empty patch".
- Measure progress: the count of fixtures with `current_patch_lines: 0`
  monotonically decreases as we improve.
- Tell users what we know we can't do yet (transparency).

## Format

`regressions.json` — see the schema-less example below; each fixture has:

| Field | Meaning |
|---|---|
| `instance_id` | SWE-bench instance slug (matches HuggingFace dataset row id) |
| `repo` | `org/repo` |
| `subset` | `SWE-bench_Verified` or `SWE-bench_Lite` |
| `current_status` | Agent-side status at last eval run (`completed` / `timeout` / `error`) |
| `current_patch_lines` | Lines in the generated unified diff. `0` = nothing produced |
| `elapsed_seconds_last_run` | Wall time of the last attempt |
| `failure_mode` | Plain-English hypothesis on why kairo-code couldn't solve it |
| `expected_resolution` | The fix we're betting on — link to milestone if applicable |

## How to consume

```bash
# From kairo-code-eval, run only the tracked regressions:
python -m kairo_eval run --agent kairo-code \
    --instances <(jq -c '.fixtures[] | {instance_id}' \
        ../kairo-code/kairo-code-examples/fixtures/swe-bench-regressions/regressions.json) \
    --prompt swe-bench-optimized

# After the run, compare against the tracked baseline:
python -m kairo_eval compare \
    ../kairo-code/kairo-code-examples/fixtures/swe-bench-regressions \
    results-vNN-10
```

If any of the tracked instances flip from `current_patch_lines: 0` →
non-zero, update `regressions.json` to record the resolution (remove the
row, leave the date in the commit message).

## How to add a new fixture

When eval surfaces a new stable failure:

1. Confirm the instance fails the same way across **at least 2 runs**
   (one-off model errors don't belong here).
2. Append to `fixtures[]` in `regressions.json` with all required fields.
3. Reference the eval `results-v*-*` directory in `source_eval_run` so the
   evidence trail is reproducible.

## Why not Java JUnit tests

Running a SWE-bench instance end-to-end requires:

- A real LLM API key (~$0.10–$2 per run on GLM-5.1 / Claude-Sonnet-4)
- A working Docker daemon (the official scoring harness uses
  pre-built per-instance images)
- 5–30 minutes wall time per instance

That's incompatible with the "every PR runs every test" model. The official
eval lives in `kairo-code-eval/.github/workflows/eval.yml` (nightly + manual);
this file is just the source of truth on **which** instances to flag.
