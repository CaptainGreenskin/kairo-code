# Path review — {{DATE}}

> Cadence: triggered after every N DONE tasks (default 20). Run by autopilot
> via `cli-dispatch-path-review`; the resulting REVIEW_<date>.md lives in
> `.dispatcher/`. Use this template to keep reviews consistent.

## 1. North-star check

- What is the project's single most important outcome over the next 4 weeks?
- Of the last N tasks marked DONE, how many directly moved that outcome?
  Surface the names that did NOT, ask whether they were necessary.

## 2. Queue health

- TODO count: ____   IN_PROGRESS: ____   BLOCKED: ____
- Oldest TODO age: ____ days
- Are BLOCKED tasks accumulating? (>5 = systemic problem; rewrite or close)
- Anything in the queue that no longer matches the north-star? Move to archive.

## 3. Executor mix

- Tasks dispatched per executor (last N): qodercli ____ / opencode ____ / self ____
- Average score per executor: ____
- Is one executor consistently <70? Stop sending it work in that profile.
- Did self-execution (orchestrator-led) creep above ~25%? Re-evaluate executor
  selection rubric.

## 4. Verify / CR signals

- Verify red rate (last N): ____ %
- CR FAIL rate: ____ %
- Top recurring CR issue: ____
- If verify-red rate > 30%, the briefs are too vague — sharpen acceptance.

## 5. Auto-decide log review

- Read tail of `.dispatcher/AUTO_DECIDE_LOG.md`. Spot-check 3 random entries:
  do you still agree with the decision? If not, capture the lesson here.

## 6. Adjustments to apply this round

- [ ] Update `.dispatcher/brief-prefix.md` with new constraint(s):
- [ ] Tighten / loosen `.dispatcher/cr-rules.sh`:
- [ ] Move stale TODOs to `.dispatcher/_archive/`:
- [ ] Other:

## 7. Decision gate

> Single sentence: "We continue / pivot / pause", with the reason.

---

After filling, run `cli-dispatch-path-review --ack` to stamp the new baseline.
