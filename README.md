# Kairo Code

**Same Models. Governable.**

Kairo Code is a governable Java Code Agent built on [Kairo](https://getkairo.dev) — the Java AI Agent framework.

## What Kairo Code IS

- A dogfood project for Kairo's SPI foundation
- An enterprise-grade code agent with audit, PII redaction, and compliance
- The first Java-native Code Agent in the ecosystem

## What Kairo Code is NOT

- A Claude Code / Codex CLI competitor on UX polish
- A 100+ model provider adapter
- A plugin marketplace
- A TUI rendering showcase

## Quick Start

```bash
mvn clean package -DskipTests
export KAIRO_CODE_API_KEY=your-api-key
java -jar kairo-code-cli/target/kairo-code-cli-0.1.0-SNAPSHOT.jar --task "fix the failing test"
```

Drop the `--task` to enter the interactive REPL.

## Slash Commands (REPL)

| Command | Purpose |
| --- | --- |
| `:help` | List commands |
| `:clear` | Drop conversation history (rebuilds session) |
| `:model <name>` | Swap the model |
| `:cost` | Show token / dollar usage so far |
| `:plan [on\|off\|toggle]` | Toggle Plan Mode — read-only review, blocks Edit/Write/Bash |
| `:skill list` | Show available + loaded skills |
| `:skill load <name>` | Inject a skill's instructions into the system prompt |
| `:skill unload <name>` | Remove a skill |
| `:snapshot save <key>` | Save current session under `~/.kairo-code/snapshots/<key>.json` |
| `:snapshot list` | List saved snapshots |
| `:snapshot delete <key>` | Remove a snapshot |
| `:resume <key>` | Restore a snapshot into the current session |
| `:exit` | Quit |

Built-in skills shipped on the classpath: `code-review`, `test-writer`,
`refactor`, `commit-message`.

Ctrl+C cancels an in-flight agent turn (does **not** exit the REPL).
Ctrl+D exits.

## Error Handling

Failures surfaced from the model or tools are categorized by
`ErrorRenderer` and rendered with a one-line diagnosis plus a concrete
next step (e.g. `:plan off`, `:clear`, `:model <name>`). Lower-layer
retries — rate-limit / server-error / prompt-too-long — are handled by
kairo's `ErrorRecoveryStrategy`; the REPL only surfaces the final
outcome.

## License

Apache License 2.0
