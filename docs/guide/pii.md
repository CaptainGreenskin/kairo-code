# PII redaction

The default guardrail chain rewrites known sensitive patterns to
`<redacted:type>` before they reach `:history`, `:session`, checkpoint, or
evolution telemetry.

Backed by [kairo-security-pii](https://github.com/captaingreenskin/kairo)
upstream — same `PiiRedactionPolicy` that any Kairo-based agent uses.

## What's redacted by default

15 patterns ship out of the box:

| Marker | Matches |
|---|---|
| `<redacted:email>` | `(?i)\b[\w.+-]+@[\w-]+\.[\w.-]+\b` |
| `<redacted:phone>` | US phone numbers (`+1-xxx-xxx-xxxx` etc.) |
| `<redacted:cn-phone>` | Chinese mobile numbers (`1[3-9]\d{9}`) |
| `<redacted:cc>` | Credit card (Luhn-ish, 13-19 digits) |
| `<redacted:ssn>` | US SSN |
| `<redacted:cn-id>` | Chinese national ID |
| `<redacted:ipv4>` | IPv4 addresses |
| `<redacted:iban>` | International bank account |
| `<redacted:api-key>` | OpenAI-style `sk-`/`ak-`/`pk-`/`rk-` keys |
| `<redacted:jwt>` | JWT bearer tokens |
| `<redacted:aws-key>` | AWS access key (`AKIA[0-9A-Z]{16}`) |
| `<redacted:aws-secret>` | AWS secret access key |
| `<redacted:private-key>` | PEM `-----BEGIN PRIVATE KEY-----` blocks |
| `<redacted:github-token>` | `gh[pousr]_*` tokens |
| `<redacted:slack-token>` | `xox[baprs]-*` tokens |

The cloud-credentials half (AWS / GitHub / Slack / PEM) was added in M-F5b
— promoted upstream from kairo-assistant's `OutputScanner` so every Kairo
agent gets the same default coverage.

## What's protected

PII redaction fires at the GuardrailPhase boundaries — **POST_MODEL** (model
output text + thinking) and **POST_TOOL** (tool result content). Plus
checkpoint serialization redacts `_streaming_result` (M-B4 fix) so
streaming-path tool snapshots don't leak.

Not redacted: agent's own tool *inputs* (the agent might WRITE a credential
on purpose), system prompt, conversation history sent BACK to the model.

## Disable for debugging

```bash
export KAIRO_PII_REDACTION=off
kairo-code
```

Re-enable: unset the env var.

## Add custom patterns

Wire your own `PiiRedactionPolicy` via the SDK:

```java
PiiRedactionConfig config = PiiRedactionConfig.of(
        PiiPattern.EMAIL,
        PiiPattern.AWS_ACCESS_KEY,
        // ...
).withOrder(50);  // run BEFORE the default policy at order 100

var customPolicy = new PiiRedactionPolicy(config);
// add to your GuardrailChain
```

For *new* patterns that should benefit everyone, PR them into upstream
`kairo-security-pii/PiiPattern.java` — same precedent as M-F5b's
cloud-creds additions.

## Verify it's working

```bash
echo 'api_key=AKIAIOSFODNN7EXAMPLE' > secrets.txt
kairo-code --task "read secrets.txt and show me what it has"
# Expected: response contains <redacted:aws-key>, not the raw value
```

If the raw value leaks: check `KAIRO_PII_REDACTION` isn't set to `off`, and
that you're on kairo-code ≥ 0.2.0.

## Other policies in the chain

PII is one of four default policies. The others (also opt-out via the same
`KAIRO_PII_REDACTION=off` toggle):

- **DangerousCommandPolicy** — blocks `rm -rf /`, `mkfs`, fork bombs, etc.
  on bash/shell/code_execute tools
- **PathTraversalPolicy** — blocks `../` in file tool paths, writes to
  `/etc/passwd` etc.
- **ToolLoopDetectionPolicy** — warns when the model calls the same tool +
  args ≥3 times, denies at ≥5

All three live in
[kairo-core/guardrail/policy](https://github.com/captaingreenskin/kairo)
— PR a fix there if you hit a false positive.
