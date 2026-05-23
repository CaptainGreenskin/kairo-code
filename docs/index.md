---
layout: home

hero:
  name: Kairo Code
  text: Same Models. Governable.
  tagline: A Java Code Agent built on Kairo. Enterprise-grade audit, PII redaction, and compliance baked in.
  actions:
    - theme: brand
      text: Quick start
      link: /guide/quickstart
    - theme: alt
      text: View on GitHub
      link: https://github.com/captaingreenskin/kairo-code

features:
  - icon: 🛡
    title: Governable by default
    details: PII redaction, dangerous-command blocking, path-traversal protection, and tool-loop detection ship as a default guardrail chain. Set KAIRO_PII_REDACTION=off to disable.
  - icon: 🔌
    title: 30+ REPL commands
    details: ":help / :skill / :plugin / :mcp / :expert / :cron / :lsp / :evolve — full lifecycle in a single binary."
  - icon: 📦
    title: Three install paths
    details: "npm install -g @kairo/code, brew install kairo-code, or java -jar — same fat jar under the hood."
  - icon: 🧰
    title: Embeddable Java SDK
    details: "KairoCodeClient.builder().apiKey(...).build().task(\"...\") — no CLI needed. Use from IDE plugins, CI scripts, evals."
  - icon: 🔭
    title: OpenTelemetry-native
    details: Set OTEL_EXPORTER_OTLP_ENDPOINT and every agent call / tool invocation / reasoning step becomes a span.
  - icon: 🧠
    title: Same upstream as Kairo Assistant
    details: All capability modules (security, evolution, plugin, lsp, gateway, observability) come from kairo upstream. Patches flow back automatically.
---
