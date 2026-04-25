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

## License

Apache License 2.0
