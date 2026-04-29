# kairo-code + Claude Provider 配置指南

## 快速启动

```bash
KAIRO_CODE_API_KEY=sk-ant-... \
KAIRO_CODE_BASE_URL=https://api.anthropic.com \
KAIRO_CODE_MODEL=claude-sonnet-4-6 \
java -jar kairo-code-cli.jar --provider=anthropic --working-dir . --task "..."
```

## 配置参数

| 环境变量 | 说明 | 示例值 |
|---------|------|--------|
| `KAIRO_CODE_API_KEY` | Anthropic API Key（必填） | `sk-ant-api03-...` |
| `KAIRO_CODE_BASE_URL` | API 基础 URL | `https://api.anthropic.com` |
| `KAIRO_CODE_MODEL` | 模型名称 | `claude-sonnet-4-6` |
| `KAIRO_CODE_PROVIDER` | Provider 类型 | `anthropic` |

也可以通过 CLI 参数指定：

```bash
java -jar kairo-code-cli.jar \
  --provider=anthropic \
  --api-key=sk-ant-... \
  --base-url=https://api.anthropic.com \
  --model=claude-sonnet-4-6 \
  --working-dir . \
  --task "Write a Fibonacci function in Java"
```

## 推荐模型

| 模型 | 说明 |
|------|------|
| `claude-sonnet-4-6` | 最新 Sonnet，性价比高，推荐日常使用 |
| `claude-sonnet-4-20250514` | 稳定版 Sonnet |
| `claude-opus-4-6` | 最强推理能力，适合复杂任务 |
| `claude-opus-4-20250514` | 稳定版 Opus |

## 架构说明

kairo-code 通过 `io.kairo.core.model.anthropic.AnthropicProvider` 与 Anthropic Messages API 对接。
主要适配点：

### 消息格式适配

- **System Prompt**: Anthropic 使用顶层 `system` 字段（不是 messages 数组中的 system role 消息）
- **Tool Use**: 序列化为 `{"type": "tool_use", "id": "...", "name": "...", "input": {...}}`
- **Tool Result**: 在 user role 消息中，序列化为 `{"type": "tool_result", "tool_use_id": "...", "content": "..."}`
- **max_tokens**: 必填字段，默认 8096

### Streaming 支持

Anthropic provider 完整支持 SSE 流式响应，包括：
- 文本增量输出（text_delta）
- 扩展思考（thinking_delta）
- 工具调用 JSON 增量接收（input_json_delta）
- Prompt caching 指标

### Prompt Caching

自动启用 Anthropic prompt caching：
- system prompt 的静态部分标记 `cache_control: {"type": "ephemeral"}`
- 最后一个 tool definition 也标记 cache_control

## 已知限制

- **Extended Thinking**: 当模型支持 thinking 时自动启用，预算根据对话复杂度动态调整
- **Rate Limiting**: 429 错误会自动重试（默认最多 3 次）
- **API Version**: 固定使用 `2023-06-01` API 版本

## 故障排查

### "model not found" 错误

检查模型名称是否正确。Anthropic 模型名格式为 `claude-sonnet-4-6`（不是 `claude-sonnet-4`）。

### "max_tokens is required" 错误

确保 `ModelConfig` 中设置了 `maxTokens`。kairo-code 默认使用 8096，一般不需要手动配置。

### 工具调用后模型不响应

确认 tool_result 的 `tool_use_id` 与之前 assistant 消息中 `tool_use` 的 `id` 完全匹配。
