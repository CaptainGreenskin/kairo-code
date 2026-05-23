# Quick start

Goal: get a working REPL talking to a model in under five minutes.

## 1. Install

Pick one:

::: code-group
```bash [npm]
npm install -g @kairo/code
```
```bash [Homebrew]
brew tap captaingreenskin/kairo
brew install kairo-code
```
```bash [Direct jar]
# Requires Java 17+
wget https://github.com/captaingreenskin/kairo-code/releases/download/v0.2.0/kairo-code-cli-0.2.0-SNAPSHOT.jar
alias kairo-code='java -jar $(pwd)/kairo-code-cli-0.2.0-SNAPSHOT.jar'
```
:::

## 2. Set an API key

Any OpenAI-compatible provider works. Pick whichever you have access to:

::: code-group
```bash [OpenAI]
export KAIRO_CODE_API_KEY=sk-...
# baseUrl defaults to https://api.openai.com
```
```bash [Anthropic]
export KAIRO_CODE_API_KEY=sk-ant-...
export KAIRO_CODE_BASE_URL=https://api.anthropic.com
```
```bash [MiniMax M2]
export KAIRO_CODE_API_KEY=sk-cp-...
export KAIRO_CODE_BASE_URL=https://api.minimaxi.com
export KAIRO_CODE_CHAT_PATH=/v1/chat/completions
```
```bash [Zhipu GLM Coding]
export KAIRO_CODE_API_KEY=...
export KAIRO_CODE_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4
export KAIRO_CODE_CHAT_PATH=/chat/completions
```
:::

::: tip
Persistent config: drop `api-key=...`, `base-url=...`, `chat-path=...` lines
into `~/.kairo-code/config.properties` instead of `export`ing each time.
:::

## 3. Run

```bash
# Interactive REPL
kairo-code

# One-shot task
kairo-code --task "fix the failing test in src/main/java/Foo.java" --model gpt-4o
```

You should see:

```
Kairo Code v0.2.0 — Same Models. Governable.
Type your request, or :help for commands. :exit to quit.

kairo-code>
```

Try `:help` for the full command list, then ask it something:

```
kairo-code> explain how the build is wired
```

## 4. Verify governance is on

Create a file with a fake credential and ask the agent to summarize it:

```bash
echo 'aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY' > secrets.txt
kairo-code --task "read secrets.txt and tell me what's inside"
```

Expected output — the credential is rewritten before the model's response
ever lands in your terminal:

```
The file contains: aws_secret_access_key=<redacted:aws-secret>
```

That's [PII redaction](./pii) — on by default. Set `KAIRO_PII_REDACTION=off`
to disable when debugging.

## Next

- [Configuration](./configuration) — flags, env vars, config file
- [REPL workflow](./repl) — slash commands tour
- [Java SDK](./sdk) — embed Kairo Code as a library
- [Observability](./observability) — wire OTLP traces
