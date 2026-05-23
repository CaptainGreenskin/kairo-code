# Contributing to Kairo Code

Thanks for your interest. This doc covers the practical setup; the design
philosophy lives in the [Kairo upstream contributing guide](https://github.com/captaingreenskin/kairo/blob/main/CONTRIBUTING.md)
and applies here too.

## Prerequisites

- **JDK 17+** (21 recommended) — Temurin is what CI uses
- **Maven 3.9+** — for building / testing
- **Node 18+** + **npm** — only if you touch `kairo-code-web/` or `docs/`

## First-time setup

```bash
git clone https://github.com/captaingreenskin/kairo-code.git
cd kairo-code

# 1. Install kairo upstream (kairo-code consumes its SPI)
git clone https://github.com/captaingreenskin/kairo.git ../kairo
(cd ../kairo && mvn install -DskipTests)

# 2. Build kairo-code
mvn clean install -DskipTests

# 3. Verify CLI smoke
java -jar kairo-code-cli/target/kairo-code-cli-*-SNAPSHOT.jar --help
```

## Making a change

```bash
git checkout -b feat/<concise-name>
# edit
mvn test -pl <touched-module>
git commit -m "feat(<module>): <what changed and why>"
git push -u origin feat/<concise-name>
gh pr create
```

Commit message format follows
[Conventional Commits](https://www.conventionalcommits.org/):

```
feat(kairo-code-core): wire LspService into WriteTool / EditTool
fix(kairo-code-cli): KairoCodeMainOptionTest isolates user.home
test(kairo-security-pii): cover 6 cloud-credential patterns
docs(observability): document OTLP setup
chore(deps): bump opentelemetry-exporter-otlp to 1.61.0
```

## Reverse-downstream rule

If your change introduces a capability that **any Kairo agent** would benefit
from (not just kairo-code), put it in the upstream
[kairo](https://github.com/captaingreenskin/kairo) repo, then consume it here.
Examples that followed this rule in 0.2.0:

- `kairo-core/guardrail/policy/*` (3 generic policies) — originated in
  kairo-assistant, promoted upstream
- `kairo-security-pii/PiiPattern` — 6 cloud-credential patterns added upstream
- `kairo-lsp/BuiltInServers` — JDT_LS entry added upstream
- `kairo-expert-team/ExpertTeamComposer` — generic factory promoted upstream
- `kairo-core/model/FallbackModelProvider` — multi-provider fallback upstream
- `kairo-core/session/{UnifiedGateway,AgentSessionPool,SessionKey,...}` —
  session orchestration promoted upstream

Test coverage and changelog notes belong with the upstream PR; kairo-code
just bumps the dep version + wires the new capability in.

## Tests

- `mvn test -pl <module>` — single module
- `mvn clean install -DskipTests` — verify cross-module wiring compiles
- `mvn test` from project root — everything (slow, ~3 min)
- `cd kairo-code-web && npm test -- --run` — frontend unit tests
- `cd kairo-code-web && npm run test:e2e` — Playwright

surefire is configured `forkCount=1, reuseForks=false` for determinism (see
[M-A6](./CHANGELOG.md) for why). If your test needs parallelism, scope the
override locally.

### "Unresolved compilation problems" at test runtime

If `mvn test` reports `java.lang.Error: Unresolved compilation problems` for
classes that obviously do compile (you can see `mvn compile` succeed), your
`target/` has stale incremental-build artifacts from an earlier branch.
Surefire's caching doesn't always invalidate them correctly. Fix:

```bash
mvn clean test -pl <module>
```

CI does this automatically — the smell is local-only.

## Code style

- **Java**: Google Java Format via Spotless. `mvn spotless:apply` auto-fixes.
- **TypeScript**: project uses TypeScript strict mode + vitest. No
  prescribed formatter (matches upstream React conventions).
- **Markdown** in `docs/`: kept short, scannable, code-block-heavy. Prose for
  WHY, code blocks for WHAT.

## Reviewing your own PR before submitting

```bash
# Does it build?
mvn clean install

# Did you introduce any spotless violations?
mvn spotless:check

# Did you add tests for the new behavior?
git diff --stat | grep -E '\.java$' | grep -v Test

# Did the CHANGELOG get updated?
git diff CHANGELOG.md
```

### Public API compatibility (japicmp)

`kairo-code-core` is the public SDK surface — `KairoCodeClient`,
`KairoCodeSession`, `io.kairo.code.core.config.*`. Breaking changes here
strand anyone embedding the agent. We use **japicmp** to catch incompatible
modifications against the last released artifact.

Run locally before opening a PR that touches `kairo-code-core` public API:

```bash
# Requires a published baseline in the local/CI Maven repo. Pre-release
# (before the first GitHub Release ships) this is a no-op — the plugin
# fails gracefully because there's no version to compare against.
mvn -pl kairo-code-core -Pjapicmp verify
```

A binary or source-incompatible change fails the build. If the break is
intentional (semver bump, deliberate rename), update the version + add an
explicit changelog entry. Internal packages (everything under
`io.kairo.code.core.{hook,session,task,team,plugin,...}`) are excluded
from the check — see `kairo-code-core/pom.xml` for the exact exclude list.

### `@SpringBootTest` checklist (mandatory)

If your PR adds or modifies any `@SpringBootTest`, you MUST `@Primary`-override
every bean whose name or class contains: `PersistenceService`, `Store`,
`Repository`, `Writer`, `SessionPool`, `Snapshot`, `Cron`, `Curator`,
`Memory`, or `Workspace`. Point the replacement at a `Files.createTempFile(...)`
or `@TempDir` path. The default beans all write to `~/.kairo-code/` (or
`~/.kairo-assistant/`) and **will silently overwrite developer config when
the test runs**. Reference fix: `ConfigControllerUpdateTest.MockAgentConfig.throwawayConfigPersistence()`.

Why: see [M-X7 follow-up](./CHANGELOG.md). One unisolated test once
destroyed the maintainer's GLM API key in the middle of a release.

## Areas we'd love help with

- **IDE plugins** (VS Code / JetBrains) — beyond the current ACP-based
  workflow
- **More LSP-aware tool integrations** — TypeScript / Python / Go LSP
  results captured in tool metadata
- **Plugin marketplace** — a public index of plugins users can browse
- **Web UI complex flows** — more E2E coverage for chat / expert team / approval
- **Doc translations** — currently English only

Open an issue first for anything > 50 lines so we can shape the approach
together.

## Reporting bugs

See [SECURITY.md](./SECURITY.md) for vulnerability disclosure; for ordinary
bugs use GitHub Issues with the bug-report template. Include:

- `kairo-code --version` output
- `java -version` output
- The exact command + minimal repro
- Trace from `~/.kairo-code/` or working-dir's `.kairo-trace/` if relevant
- `~/.kairo-code/config.properties` (redact the api-key first!)

## License

By contributing, you agree your work is licensed under Apache 2.0 (the same
as the project). No CLA required.
