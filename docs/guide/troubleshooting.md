# Troubleshooting

Real failure modes hit during this milestone's development. Check here first.

## "OpenAI API error: HTTP 401"

You're sending the wrong API key for the endpoint. Common causes:

1. **Shell variable expansion order** — inline env vars don't reach `$VAR`
   expansion:
   ```bash
   # WRONG — $MINIMAX_KEY expands BEFORE MINIMAX_KEY=... takes effect
   MINIMAX_KEY=sk-cp-... java -jar ... --api-key "$MINIMAX_KEY"

   # RIGHT — export first
   export MINIMAX_KEY=sk-cp-...
   java -jar ... --api-key "$MINIMAX_KEY"

   # ALSO RIGHT — use env var the CLI reads directly
   KAIRO_CODE_API_KEY=sk-cp-... java -jar ...
   ```

2. **Stale `~/.kairo-code/config.properties`** — file-based defaults are
   loaded first; CLI flags should override, but verify with:
   ```bash
   cat ~/.kairo-code/config.properties
   ```

3. **Provider mismatch** — `--base-url https://api.minimaxi.com` with an
   OpenAI key won't work. Set `--api-key` to a real MiniMax key, or remove
   the base-url override to use OpenAI.

Verify the wire by enabling JDK HTTP client logs:
```bash
java -Djdk.httpclient.HttpClient.log=requests,headers -jar ... 2>&1 | grep Authorization
```

## "tools=0" but the file got created

99% chance: the file is **stale** from a previous test run that wrote to the
same directory. Use a fresh path each time:

```bash
# WRONG — pre-existing files from yesterday's run still there
mkdir -p /tmp/test-dir

# RIGHT — fresh dir
rm -rf /tmp/test-dir && mkdir /tmp/test-dir
```

If the file content matches your prompt's exact string and trace shows tools
ARE being recorded, the framework is working. Some models (MiniMax M2 has
been observed) get stuck in `<think>` planning loops without progressing
to a write call — that's a model behavior, not a kairo bug.

## REPL hangs at "Reached max iterations"

Most likely the model is in a planning loop (see above). Mitigations:

- **Bump --max-iterations** — default 50, increase to 100 if the task is large
- **Use --plan first** — Plan Mode forces the model to think before acting
- **Switch model** — GPT-4o, Claude 3.5, GLM-5.1 generally less prone to
  thinking spirals than MiniMax M2 for write-heavy tasks

## Tests hang on `mvn test -pl kairo-code-cli`

Pre-2026-05 versions of the surefire config (`<parallel>methods</parallel>`,
`<forkCount>1.5C</forkCount>`) deadlocked on multi-core machines because
tests shared `/tmp` dirs and `System.setProperty`. M-A6 changed the config
to `<forkCount>1</forkCount>` + `<reuseForks>false</reuseForks>`.

If you see hangs:
```bash
# Verify your pom is up to date
grep -A2 "<artifactId>maven-surefire-plugin</artifactId>" pom.xml

# Nuclear: clear stuck forks
pkill -9 -f surefire
```

## ACP server hangs forever in tests

`KairoCodeMain --acp-server` reads JSON-RPC frames from stdin and **never
returns**. For unit / integration tests, set:
```java
System.setProperty("kairo.code.dryrun", "true");
```
Then `cmd.execute("--acp-server", "--api-key", "test")` returns 0 immediately
without starting the I/O loop. See `KairoCodeAcpServerTest` for the pattern.

## "GuardrailDeny" denied my legitimate command

The default guardrail chain blocks `rm -rf /`, `mkfs`, `chmod 777`, etc.
False positive? Either:

1. **Tighten the regex** in
   `kairo/kairo-core/src/main/java/io/kairo/core/guardrail/policy/DangerousCommandPolicy.java`
   and PR upstream — these patterns serve every Kairo agent.
2. **Bypass via opt-out** — passing your own custom `GuardrailChain` via
   `SessionOptions` skips the auto-wired defaults entirely.

PII redaction (`KAIRO_PII_REDACTION=off`) and dangerous-command (no env
toggle — it's safety-critical) are separate. Path traversal and loop
detection share the same `off` switch as PII because they all come from the
same default chain.

## `OTEL_EXPORTER_OTLP_ENDPOINT` set but no spans appear

Check:

1. Endpoint is **OTLP-HTTP** (port 4318), not gRPC (4317) — kairo bundles
   the HTTP exporter
2. Collector is actually running: `curl $OTEL_EXPORTER_OTLP_ENDPOINT/v1/traces`
3. Logs show "OTLP exporter initialized →" — if missing, env var wasn't
   visible to the JVM
4. Allow ~5 s for the batch span processor to flush. Force flush by exiting
   gracefully (Ctrl+D in REPL, not kill -9)

For Honeycomb / Langfuse / SigNoz vendors that need auth, set
`OTEL_EXPORTER_OTLP_HEADERS="authorization=Bearer ..."`.

## Plugin install fails with "no SourceFetcher matches"

Source spec must use a known prefix:

```bash
:plugin install github:owner/repo[#ref]   # GitHub
:plugin install npm:@scope/package[@ver]  # NPM registry
:plugin install git:https://...[#ref]     # arbitrary git
:plugin install path:./local-plugin       # filesystem
:plugin install /abs/path/to/plugin       # bare path also works
```

A bare URL without prefix won't match. Add `git:` or `github:`.

## Java version

```bash
java -version
# Must show 17 or later. If you see something older, install:
#   brew install openjdk@21      # macOS
#   apt install openjdk-21-jdk   # Debian/Ubuntu
#   Or https://adoptium.net/
```

The Homebrew formula pulls openjdk@21 automatically. The npm wrapper
checks and refuses to run with java < 17.

## Still stuck

- File an issue: https://github.com/captaingreenskin/kairo-code/issues
- Include: `kairo-code --help` output, `java -version`, the failing
  command, and `~/.kairo-code/config.properties` (redact the api-key first!)
