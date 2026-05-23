# Security Policy

## Supported versions

| Version | Status |
|---|---|
| 0.2.x | ✅ active |
| < 0.2 | ❌ no support — please upgrade |

## Reporting a vulnerability

**Do not file a public GitHub Issue.** Email
[security@kairo.dev](mailto:security@kairo.dev) (PGP key below) with:

- A description of the issue and its impact
- A minimal reproduction (command line, code snippet, sample input)
- Whether you've already disclosed to anyone else
- Optional: a proposed patch

We aim to acknowledge within 2 business days and produce a fix or
mitigation plan within 7. A CVE will be requested for any vulnerability
that could allow:

- Remote code execution via crafted prompts / plugins / MCP responses
- Sandbox escape from the WriteTool / EditTool / BashTool
- Credential exfiltration via PII guardrail bypass
- Path traversal beyond declared working directory
- Denial of service against the agent loop (infinite recursion, memory blowup)

## What's in scope

Anything that runs as `java -jar kairo-code-cli-*.jar` or the npm wrapper or
the homebrew binary or the kairo-code-server WAR:

- The CLI / REPL
- The Java SDK (`KairoCodeClient`)
- The web frontend served by kairo-code-server
- The default tool registry (bash, read, write, edit, grep, glob, etc.)
- The default guardrail chain (PII, dangerous command, path traversal,
  tool loop)
- The MCP client / server
- Plugin sandbox (whatever a malicious plugin can do)

Out of scope (please report to the right place):

- Upstream Kairo SPI bugs → file at https://github.com/captaingreenskin/kairo
- Model-side issues (jailbreaks, training data leaks) → report to the
  model provider (OpenAI / Anthropic / etc.)
- Issues in the Web UI's third-party dependencies (Monaco, xterm) →
  report to those projects upstream and we'll bump the version

## What's not a vulnerability

- A skill / plugin that, when explicitly installed and enabled by the user,
  does what it says on the tin. Installing arbitrary plugins is a trust
  decision; the manager is not a sandbox.
- A model giving you wrong advice. Validate before acting on it.
- The default guardrail blocking a "legitimate" command (`rm -rf /tmp/foo`).
  That's a config issue — see [PII docs](./docs/guide/pii) for how to
  customize.

## Disclosure timeline

| Day | Action |
|---|---|
| 0 | You report. We acknowledge. |
| 1-7 | We investigate + draft fix or mitigation. |
| 7-14 | Patch released to main + cherry-picked to active branches. |
| 14-30 | Coordinated disclosure: public advisory, CVE, blog post. |
| 30+ | Hall of fame credit for the reporter (opt-in). |

We aim for the 90-day industry-standard for full disclosure but will move
faster on actively-exploited bugs.

## PGP key

Pending. Email plain text — we'll switch to encrypted reply if you
include your public key.

## Past advisories

None yet (the project is too young). This document will be updated as
they accrue.
