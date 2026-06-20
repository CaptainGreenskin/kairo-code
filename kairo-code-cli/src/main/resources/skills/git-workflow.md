---
name: git-workflow
version: 1.0.0
category: CODE
triggers:
  - "git workflow"
  - "git"
  - "/git"
---
# Git Workflow

**Branch Strategy**
- `main` — always deployable
- `feat/<name>` — feature branches from main
- `fix/<name>` — bug fix branches
- `release/<version>` — release stabilization (if needed)

**Commit Convention** (Conventional Commits)
```
<type>(<scope>): <description>

feat(auth): add JWT token refresh
fix(api): handle null response from upstream
docs(readme): update installation guide
refactor(core): extract validation logic
test(user): add edge case for empty input
chore(deps): bump spring-boot to 3.4.1
```

**PR Best Practices**
- Title: short, imperative ("Add user search" not "Added user search")
- Body: What changed, Why, How to test
- Keep PRs small (< 400 lines). Split large changes.
- Self-review before requesting review.

**Rules**
- Never force-push to main/master
- Rebase feature branches on main before merging
- Squash merge for clean history
- Delete branches after merge
