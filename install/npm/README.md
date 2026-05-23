# @kairo/code

> Same Models. Governable. — A Java Code Agent built on
> [Kairo](https://github.com/captaingreenskin/kairo).

The npm package is a thin Node.js launcher: it ensures a JDK 17+ is available,
downloads the kairo-code runtime jar on first use (cached in
`~/.kairo-code/runtime/`), then forwards every CLI argument to the jar.

## Install

```bash
npm install -g @kairo/code
```

## Use

```bash
# Interactive REPL
kairo-code

# One-shot task
kairo-code --task "fix the failing test"

# With explicit model / provider
kairo-code --provider openai \
           --base-url https://api.minimaxi.com \
           --chat-path /v1/chat/completions \
           --model MiniMax-M2 \
           --api-key "$MINIMAX_API_KEY" \
           --task "create hello.txt with text hi"
```

All CLI flags (`--help` for the full list) are forwarded verbatim to the jar.

## Requirements

- Node.js ≥ 18 (just for the launcher)
- Java ≥ 17 on PATH (the agent runtime — install via
  [Adoptium](https://adoptium.net/), `brew install openjdk@21`, etc.)

## Configuration

The launcher reads:

| Env var | Purpose | Default |
|---|---|---|
| `KAIRO_CODE_JAR_URL` | Override the download URL (private mirror / proxy) | `https://github.com/captaingreenskin/kairo-code/releases/download/v<version>/kairo-code-cli-<version>-SNAPSHOT.jar` |

The agent itself reads its own env vars — `KAIRO_CODE_API_KEY`,
`KAIRO_CODE_BASE_URL`, `KAIRO_PII_REDACTION`, `KAIRO_STREAMING`,
`OTEL_EXPORTER_OTLP_ENDPOINT`, etc. See the kairo-code docs for the full list.

## License

Apache-2.0
