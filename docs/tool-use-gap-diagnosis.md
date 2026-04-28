# Tool-Use Gap 诊断报告

## 工具注册状态

| 工具 | 注册状态 | Description | Category | SideEffect |
|------|----------|-------------|----------|------------|
| **write** | 已注册 (`CodeAgentFactory.java:135`) | "Create or overwrite a file with the given content. Automatically creates parent directories." | FILE_AND_CODE | WRITE |
| **edit** | 已注册 (`CodeAgentFactory.java:136`) | "Make precise text replacements in a file. The original text must be unique in the file." | FILE_AND_CODE | WRITE |
| **bash** | 已注册 (`CodeAgentFactory.java:133`) | "Execute a shell command and return its output. Use for running programs, installing packages, or system operations." | EXECUTION | SYSTEM_CHANGE |
| read | 已注册 | "Read file contents" | FILE_AND_CODE | READ_ONLY |
| grep | 已注册 | 搜索文件内容 | FILE_AND_CODE | READ_ONLY |
| glob | 已注册 | 查找文件 | FILE_AND_CODE | READ_ONLY |
| todo_write | 已注册 | 替换任务列表 | GENERAL | WRITE |
| tree | 已注册 | 显示目录树 | GENERAL | READ_ONLY |

**结论：write/edit 工具均已正确注册，工具描述清晰，参数 schema 正确（path + content / path + originalText + newText）。工具注册不是问题根源。**

## 系统提示词分析

### 是否显式要求使用工具写文件：**是**

系统提示词 (`system-prompt.md`) 包含以下相关段落：

```markdown
## Capabilities
- **write**: Create or overwrite files
- **edit**: Make targeted edits to existing files

## Workflow
1. **Understand** the task by reading relevant files
2. **Plan** your approach before making changes
3. **Implement** changes incrementally
4. **Verify** by running tests or checking output

## Execution Discipline
- When given a task, **immediately use tools to investigate**
- Writing a todo list is the **start** of work, not the end. After creating todos,
  immediately begin executing them with tools.
- **Prefer dedicated tools over bash** when one fits

## Edit Tool Discipline
- **Always read before editing**
- **Exact match required**
```

### 提示词评估

提示词已经相当完善，包含了 Claude Code 风格的 execution discipline 约束。
**但存在一个关键缺失：没有针对 write/edit 工具的显式强制指令。**

具体来说：
- 提示词说 "Implement changes incrementally" 但没有说 **"必须使用 write 或 edit 工具来实现变更"**
- 提示词说 "Prefer dedicated tools over bash" 但没有说明 **"不能使用 bash 的 echo/cat 来写文件"**
- 没有明确的 "你必须使用 write_file 或 edit_file 工具来创建和修改文件" 这样的强制语句

## 根本原因假设（按可能性排序）

### 假设 1（最可能）：GLM-5.1 的 CONCISE 模式导致工具描述被截断

**证据：**
- `ModelCapabilityRegistry.java:144-150` 中，GLM 模型（glm-4, glm-4-plus）的 `ToolVerbosity` 被设置为 `CONCISE`
- `ToolDescriptionAdapter.java:56-74` 中，`conciseTool()` 方法会将工具描述截断到第一个句号或 100 字符
- write 工具原始描述："Create or overwrite a file with the given content. Automatically creates parent directories."
- 截断后："Create or overwrite a file with the given content."
- edit 工具原始描述："Make precise text replacements in a file. The original text must be unique in the file."
- 截断后："Make precise text replacements in a file."

**影响：** 虽然截断后的描述仍然准确，但丢失了重要的上下文信息（如 "Automatically creates parent directories"）。对于工具使用经验不足的模型，这种信息丢失可能降低工具调用的信心。

**但是**：这不太可能是根本原因，因为截断后的描述仍然足以让模型理解工具功能。

### 假设 2（最可能）：GLM-5.1 缺乏 OpenAI 兼容的 tool calling 训练

**核心假设：GLM-5.1 通过 OpenAI 兼容 API 调用，但 GLM-5.1 对 OpenAI function calling 格式的支持不完善。**

**证据：**
- kairo-code 使用 `OpenAIRequestBuilder` 将工具以 OpenAI function calling 格式发送给模型
- GLM-5.1 能够调用 todo_write、tree、bash 工具（11 步中 3+1+7=11 次调用）
- 但从未调用 write/edit 工具

**关键分析：** GLM-5.1 确实能调用工具（bash 被调用了 7 次），说明 tool calling 机制本身是工作的。问题更可能是：

1. **GLM-5.1 倾向于用 bash 完成任务**：当需要写文件时，GLM-5.1 可能尝试用 `echo "..." > file` 或 `cat <<EOF > file` 的 bash 命令来写入，而不是调用 write 工具。这与 bash 被调用 7 次但 write/edit 为 0 次的观察一致。

2. **bash 工具是否允许文件写入？** 查看 `BashTool.java`，它执行任意 shell 命令，**完全允许** `echo "..." > file.java` 或 `cat <<EOF > file` 这类写入操作。这意味着 GLM-5.1 可能试图通过 bash 写文件但失败了（路径错误、语法错误、或者沙箱限制）。

### 假设 3：系统提示词中 "Prefer dedicated tools over bash" 指令被 GLM-5.1 忽略

系统提示词明确说：
> "Prefer dedicated tools over bash when one fits: use read to read a file, grep to search content, glob to list files — do not shell out with cat, grep, or find."

**但注意**：这里只提到了 `cat`, `grep`, `find`，没有明确提到 `echo`, `cat >`, `tee` 等写入命令。GLM-5.1 可能认为用 `echo > file` 写文件是合理的 bash 用法。

### 假设 4：PlanWithoutActionHook 未能有效干预

`PlanWithoutActionHook` 检测 `todo_write` + 无 implementation tool 的情况并注入 corrective message。

**但注意**：`IMPLEMENTATION_TOOLS` 集合包含 `"bash"`：
```java
Set.of("bash", "read_file", "write_file", "edit_file", "search_files", "glob")
```

因为 bash 被视为 implementation tool，当 GLM-5.1 调用 bash 时，hook 认为模型已经在"执行"，不会注入 corrective message。**这意味着 hook 未能识别"用 bash 代替 write/edit"这种模式。**

### 假设 5：GLM-5.1 的 promptGuidance 为空

查看 `ModelCapabilityRegistry`，GLM 模型的 `promptGuidance` 参数为 `null`。相比之下，Claude 模型有完整的 prompt guidance。这意味着没有模型特定的行为指导注入到系统提示词中。

## 推荐修复方案

### 方案 A（最小改动）：增强系统提示词 + 修复 PlanWithoutActionHook

**改动 1：在系统提示词中添加显式的 write/edit 强制指令**

在 `system-prompt.md` 的 "Execution Discipline" 部分添加：
```markdown
- **You MUST use `write` or `edit` tools to create and modify files.**
  Never use `bash` with `echo >`, `cat >`, `tee`, or heredoc to write files.
  `bash` is for running commands, not for file creation or modification.
```

**改动 2：修复 PlanWithoutActionHook 的 IMPLEMENTATION_TOOLS**

将 `"bash"` 从 `IMPLEMENTATION_TOOLS` 中移除，或者添加一个单独的检查：如果只有 bash 被调用且 bash 命令包含 `>`, `>>`, `cat <<`, `tee` 等写入模式，则视为"伪 implementation"。

或者更简单地，添加一个新的检测逻辑：
```java
// 如果连续 N 个 turn 都没有 write/edit 调用，注入 corrective message
private static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file");
```

**改动 3：为 GLM-5.1 添加 promptGuidance**

在 `ModelCapabilityRegistry` 中为 GLM-5.1 注册 entry，设置 `promptGuidance` 为：
```
You are a code editing agent. You MUST use the write and edit tools to create
and modify files. Do not attempt to write files using bash commands.
```

**预期效果**：GLM-5.1 得分从 8/100 提升到 40-60/100。

### 方案 B（系统性）：添加 NoWriteDetectedHook

创建一个新的 hook，在 batch 结束时检查：
1. 是否有任何文件被 write/edit 工具修改过
2. 如果任务要求修改文件但 write/edit 调用次数为 0，注入强纠正消息

```
CRITICAL: You have not used the write or edit tools to modify any files.
The task requires you to implement EventDispatcher by modifying EventDispatcher.java.
You MUST call write_file or edit_file to make changes. Do NOT use bash echo/cat
to write files. Immediately read the file, then use edit_file to implement the changes.
```

### 方案 C（长期）：评估 GLM-5.1 的 tool calling 能力

在 L2 修复后，运行一个简化的 benchmark：
1. 给定一个明确的 "write 'hello world' to /tmp/test.txt" 任务
2. 观察 GLM-5.1 是否调用 write 工具

如果 GLM-5.1 在简单任务中也不调用 write 工具，说明问题出在 GLM-5.1 的 OpenAI function calling 兼容性上，需要考虑：
- 切换到 GLM 原生 tool calling API（如果存在）
- 或切换到支持 function calling 更好的模型（如 GLM-4-Plus 或 Claude）

## 总结

| 维度 | 状态 |
|------|------|
| 工具注册 | write/edit/bash 均正确注册 |
| 工具描述 | 描述准确但被 CONCISE 模式截断 |
| 系统提示词 | 包含 execution discipline 但缺少 write/edit 强制指令 |
| Bash 工具 | 允许任意 shell 命令，包括文件写入 |
| Hook 干预 | PlanWithoutActionHook 将 bash 视为 implementation tool，无法检测"用 bash 替代 write"的情况 |
| 模型配置 | GLM-5.1 的 promptGuidance 为空 |

**最可能的根本原因组合**：GLM-5.1 倾向于使用 bash 来完成文件写入（因为它可以），而系统提示词没有明确禁止这种行为，且 PlanWithoutActionHook 未能检测到这种情况。三者叠加导致 GLM-5.1 绕过了 write/edit 工具。
