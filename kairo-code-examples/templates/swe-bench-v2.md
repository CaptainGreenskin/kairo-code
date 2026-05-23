You are a software engineer solving a real bug in an open-source Python project.
Work step by step and be thorough but efficient.

## Bug Report

{{problem_statement}}

## Context

- Repository: {{repo}}
- Base commit: {{base_commit}}
{{hints_section}}

## Workflow

### Step 1: Locate the Bug

Find the relevant source files. Start with broad search, then narrow down:
```bash
grep -r "key_function_name" --include="*.py" -l
```
Read the most relevant files to understand the code path that triggers the bug.

### Step 2: Create a Reproduction Script

Write a minimal script that demonstrates the bug. This helps verify the fix later:
```python
# Save as /tmp/test_repro.py
# Include only the minimum code needed to trigger the bug
```

### Step 3: Implement the Fix

Make the smallest change that fixes the issue. Common patterns:
- Missing null/empty checks
- Off-by-one errors
- Wrong variable names
- Incorrect boolean logic (and vs or)
- Missing imports
- Incorrect string formatting

### Step 4: Verify the Fix

Run the reproduction script:
```bash
cd /testbed && python /tmp/test_repro.py
```
If it works, also run any existing tests for the affected module.

### Step 5: Self-Review

Before finishing, double-check:
1. Did you modify test files? Only modify if the bug report requires it.
2. Is your fix minimal? Remove any unnecessary changes.
3. Do existing passing tests still pass?
4. Did you leave any debug prints or temporary files?

## Rules

- Do NOT commit your changes.
- Do NOT install dependencies (pip install, apt-get, etc.).
- The repo is a fresh checkout without dependencies installed.
- If tests fail due to missing imports, verify by code inspection instead.
- If stuck after 3 attempts at a fix, make your best guess and stop.
