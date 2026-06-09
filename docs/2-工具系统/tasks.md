# tasks.md · 工具系统

## 概述

13 个任务，按依赖顺序排列。每个任务标注影响文件和依赖。

**设计要点**（实现时遵守）：
- 所有操作文件的工具构造函数接收 `Path projectRoot`，相对路径 resolve 到根目录
- `ExecuteCommandTool` 接收 `Function<String, Boolean>` 确认回调，不直接依赖 TerminalIO
- `LLMProvider.streamChat()` 接收 `List<Tool>`，各 Provider 自行转为 API 格式
- `ToolRegistry` 只管存取，不做 API 格式转换

---

### 任务 1 · 定义核心工具抽象（Tool / ToolCall / ToolResult）

**影响文件**
- `src/main/java/com/bingtangcode/tool/Tool.java` — 工具接口
- `src/main/java/com/bingtangcode/tool/ToolResult.java` — 执行结果
- `src/main/java/com/bingtangcode/tool/ToolCall.java` — 一次工具调用

**依赖**: 无（纯模型层，不碰现有代码）

**内容**
- `Tool` 接口：`getName()`、`getDescription()`、`getParametersSchema()` 返回 JSON Schema 字符串、`execute(Map<String,Object>)`
- `ToolResult`：toolCallId、content（文本）、isError（布尔），与 Anthropic tool_result 格式一致
- `ToolCall`：id、name、parameters（Map），从 LLM 流式解析得到

---

### 任务 2 · 扩展 Message 模型支持工具调用和工具结果

**影响文件**
- `src/main/java/com/bingtangcode/llm/Message.java`
- `src/main/java/com/bingtangcode/llm/Role.java`（按需）

**依赖**: 任务 1

**内容**
- Message 增加 `toolCalls`（List<ToolCall>）和 `toolResults`（List<ToolResult>）字段
- 保留 `Message(Role, String)` 构造器向下兼容已有代码
- 纯文本消息 toolCalls/toolResults 为空 List

---

### 任务 3 · 实现 ToolRegistry（工具注册中心）

**影响文件**
- `src/main/java/com/bingtangcode/tool/ToolRegistry.java`

**依赖**: 任务 1

**内容**
- `register(Tool)` 登记工具，同名覆盖抛异常
- `get(String name)` 按名查找，未找到返回 null
- `getAll()` 返回不可变列表，供各 LLMProvider 遍历并自行适配为 API 格式
- 不做 API 格式转换——那是各 Provider 适配层的职责

---

### 任务 4 · 实现六个核心工具

**影响文件**（新建 package `com.bingtangcode.tool.tools`）
- `ReadFileTool.java` — 读文件，支持 startLine/endLine 可选参数
- `WriteFileTool.java` — 创建或覆盖写文件
- `EditFileTool.java` — 原文唯一匹配替换
- `ExecuteCommandTool.java` — 执行 shell 命令
- `FindFilesTool.java` — glob 模式找文件
- `SearchContentTool.java` — 正则/关键词搜索文件内容

**依赖**: 任务 1
**参考**: `spec.md` 能力清单 #3，各工具参数与行为描述

**内容**
- 每个工具实现 Tool 接口，构造函数接收 `Path projectRoot`
- `ReadFileTool`：参数 filePath(必填)、startLine(可选)、endLine(可选)；startLine/endLine 为 1-based，返回带行号前缀的内容
- `WriteFileTool`：参数 filePath(必填)、content(必填)；路径 resolve 到项目根，拒绝 `../` 越界
- `EditFileTool`：参数 filePath(必填)、oldString(必填)、newString(必填)；读文件全部行 → 逐行扫描找 oldString → 唯一定位则替换后写回；0 处匹配报 "未找到匹配文本"，多处于匹配报 "匹配到 N 处，请缩小范围"
- `ExecuteCommandTool`：构造函数额外接收 `Function<String, Boolean> confirmationHook`（可为 null 跳过确认）；执行前若 hook 非空则回调 hook.apply(command)，返回 false 则拒绝执行；用 ProcessBuilder 执行，工作目录为 projectRoot，收集 stdout + stderr + exitCode
- `FindFilesTool`：参数 pattern(必填)、directory(可选，默认 projectRoot)；用 `Files.walkFileTree` + `FileSystem.getPathMatcher("glob:" + pattern)`，结果限制 200 条
- `SearchContentTool`：参数 query(必填)、directory(可选)、filePattern(可选)；用 `Files.walk` + `Files.readAllLines` + `String.contains`，返回 `文件:行号: 内容` 格式

---

### 任务 5 · 实现 ToolExecutor（超时 + 错误包装）

**影响文件**
- `src/main/java/com/bingtangcode/tool/ToolExecutor.java`
- `src/main/java/com/bingtangcode/config/ConfigManager.java`（新增 toolTimeout 配置项）

**依赖**: 任务 1

**内容**
- `execute(Tool tool, String toolCallId, Map<String, Object> params)` → ToolResult
- 用 `ExecutorService` + `Future.get(timeout, TimeUnit)` 施加统一超时（默认 30s，从 ConfigManager 读取）
- 超时 → 强制中断（future.cancel(true)），返回 ToolResult(isError=true, content="工具执行超时（30秒）")
- 异常 → 捕获 Exception，返回 ToolResult(isError=true, content="执行失败: {exception.getMessage()}")
- 正常 → 返回工具自己的 ToolResult，注入 toolCallId

**设计说明：为什么 ToolExecutor 要再开一个线程？**

不是为了让工具异步执行，而是为了**超时控制**。如果 provider 线程直接调用 `tool.execute()`，工具里万一有死循环或 `sleep 999`，provider 线程（单线程 executor）就永远卡死——没有外部中断手段。

开一个独立的 tool-executor 线程后，provider 线程用 `Future.get(30s)` 阻塞等待：工具正常结束则拿结果，超时则 `future.cancel(true)` 强制中断 tool-executor 线程并返回"工具执行超时"。本质是用可中断的阻塞等待替代不可控的直接调用。

```
provider 线程                        tool-executor 线程
     │                                      │
     ├─ future.get(30s) ──阻塞等待──→       ├─ tool.execute(params)
     │                                      │
     ├─ 30秒到了，TimeoutException！         │
     ├─ future.cancel(true) ──强制中断──→   ├─ 线程终止
     └─ 返回"工具执行超时"给模型
```

---

### 任务 6 · 扩展 StreamCallback 支持工具调用事件

**影响文件**
- `src/main/java/com/bingtangcode/llm/StreamCallback.java`

**依赖**: 任务 1

**内容**
- 新增 `onToolCall(ToolCall toolCall)` — 当 LLM 流式解析完一个完整 tool_call 时回调
- 保留已有的 `onToken(String)`、`onComplete()`、`onError(Exception)` 向后兼容

---

### 任务 7 · 更新 LLMProvider 接口

**影响文件**
- `src/main/java/com/bingtangcode/llm/LLMProvider.java`

**依赖**: 任务 1（Tool 类型）、任务 6（StreamCallback 已有 onToolCall）

**内容**
- `streamChat()` 签名改为 `void streamChat(List<Message> messages, List<Tool> tools, StreamCallback callback)`
- 两个实现类会在任务 8/9 中适配，此处只需更新接口声明
- 纯文本对话传空 List 即可向后兼容

---

### 任务 8 · AnthropicProvider 工具调用支持

**影响文件**
- `src/main/java/com/bingtangcode/llm/LLMimpl/AnthropicProvider.java`

**依赖**: 任务 1、任务 2、任务 7
**参考**: `AnthropicProvider.java:110-175`（现有 SSE 解析逻辑）、Anthropic Messages API `content_block_start`/`content_block_delta` 事件

**内容**
- 实现任务 7 的新接口签名；从 `Tool` 列表构造 Anthropic 格式的 tools 数组（`name`、`description`、`input_schema`——注意 Anthropic 叫 input_schema 不叫 parameters）
- 请求体加入 `"tools": [...]`，不显式设 tool_choice
- SSE 解析新增：`content_block_start`（type=tool_use）记录 id 和 name；`content_block_delta`（type=input_json_delta）拼接 partial_json；完整的 tool_use 块构造 ToolCall，回调 `onToolCall()`
- `stop_reason: "tool_use"` 时不再追加纯文本 assistant 消息

**Anthropic Messages API 完整格式参考**

HTTP 请求：
```
POST https://api.anthropic.com/v1/messages
Headers:
  x-api-key: <api_key>
  anthropic-version: 2023-06-01
  Content-Type: application/json
```

请求体（纯文本对话）：
```json
{
  "model": "claude-opus-4-7",
  "max_tokens": 32768,
  "stream": true,
  "system": "你是 bingtangCode，一个终端 AI 编程助手...",
  "messages": [
    {"role": "user", "content": "帮我读一下 pom.xml"}
  ]
}
```

请求体（带 tools）：
```json
{
  "model": "claude-opus-4-7",
  "max_tokens": 32768,
  "stream": true,
  "system": "...",
  "tools": [
    {
      "name": "read_file",
      "description": "读取项目中指定文件的内容，返回带行号前缀的文本...",
      "input_schema": {
        "type": "object",
        "properties": {
          "filePath": {"type": "string", "description": "..."},
          "startLine": {"type": "integer", "description": "..."},
          "endLine": {"type": "integer", "description": "..."}
        },
        "required": ["filePath"]
      }
    }
  ],
  "messages": [
    {"role": "user", "content": "帮我读一下 pom.xml"}
  ]
}
```

注意：Anthropic 用 `input_schema`（不是 OpenAI 的 `parameters`），由 `Tool.getParametersSchema()` 返回的 JSON Schema 字符串解析后直接填入。

消息类型一：assistant 含 tool_calls（模型发起工具调用）：
```json
{
  "role": "assistant",
  "content": "",
  "tool_calls": [
    {
      "id": "toolu_01AbCdEfGh",
      "type": "tool_use",
      "name": "read_file",
      "input": {"filePath": "pom.xml"}
    }
  ]
}
```

消息类型二：user 含 tool_results（工具执行结果回灌）：
```json
{
  "role": "user",
  "content": [
    {
      "type": "tool_result",
      "tool_use_id": "toolu_01AbCdEfGh",
      "content": "     1\t<project>...",
      "is_error": false
    }
  ]
}
```

注意：`content` 字段是**数组**（不是字符串），每个元素是一个 tool_result 对象。`tool_use_id` 与 assistant 消息中 tool_calls 的 `id` 一一对应。

SSE 响应事件流（以一次 tool_use 为例）：
```
event: message_start
data: {"type":"message_start","message":{"id":"msg_xxx",...}}

event: content_block_start                    ← 新内容块开始
data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01AbCdEfGh","name":"read_file"}}

event: content_block_delta                    ← JSON 参数片段拼接
data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"filePath\":\""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"pom.xml\"}"}}

event: message_delta                          ← stop_reason 在 message_delta 中
data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

event: message_stop                           ← 流结束
data: {"type":"message_stop"}
```

SSE 事件类型对照：

| SSE type | 作用 | 代码处理 |
|----------|------|---------|
| `message_start` | 流开始，含 message.id | 忽略 |
| `content_block_start` | 新内容块开始。content_block.type 为 `"text"` 或 `"tool_use"` | `tool_use`→记录 id、name |
| `content_block_delta` | 块内容增量。delta.type 为 `"text_delta"`（文本）或 `"input_json_delta"`（工具参数 JSON） | text→`onToken()`，json→拼接 |
| `message_delta` | 消息级元信息。delta.stop_reason 为 `"end_turn"` \| `"tool_use"` \| `"max_tokens"` \| `"stop_sequence"` | `tool_use`→标记 flag |
| `message_stop` | 流结束 | 有 tool_use→`onToolCall()`，否则 →`onComplete()` |

纯文本回复的 SSE 流（对比）：
```
event: message_start
data: {"type":"message_start","message":{...}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好的"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，我来读一下..."}}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

event: message_stop
data: {"type":"message_stop"}
```

---

### 任务 9 · OpenAIProvider 工具调用支持

**影响文件**
- `src/main/java/com/bingtangcode/llm/LLMimpl/OpenAIProvider.java`

**依赖**: 任务 1、任务 2、任务 7
**参考**: `OpenAIProvider.java:85-155`（现有 SSE 解析逻辑）、OpenAI Chat Completions API function calling 机制

**内容**
- 实现任务 7 的新接口签名；从 `Tool` 列表构造 OpenAI 格式的 tools 数组（`{type:"function", function:{name, description, parameters}}`）
- SSE 解析新增：`delta.tool_calls` 数组，按 index 区分不同 tool_call，累积 `function.arguments` JSON 片段；`finish_reason=tool_calls` 时完成解析，构造 ToolCall，回调 `onToolCall()`

---

### 任务 10 · TUI 命令确认对话框

**影响文件**
- `src/main/java/com/bingtangcode/tui/TerminalIO.java`

**依赖**: 无（纯 UI 层）
**参考**: `TerminalIO.java:readLine()`（现有输入方法，供参考 JLine3 LineReader 用法）

**内容**
- `confirmCommand(String command)` 返回 boolean
- 渲染高亮的命令文本（ANSI 黄色加粗）+ `[y/N]` 提示
- 用 JLine3 的 `LineReader.readLine()` 读取单个字符
- y/Y 返回 true，n/N/其他返回 false（注意：危险命令默认 N，回车不算确认）
- 10 秒无输入自动返回 false

---

### 任务 11 · 更新 DialogueManager 实现工具调用流程

**影响文件**
- `src/main/java/com/bingtangcode/core/DialogueManager.java`

**依赖**: 任务 1、任务 2、任务 3、任务 4、任务 5、任务 6、任务 7
**参考**: `DialogueManager.java:streamResponse()`（现有流式回复逻辑）、`spec.md` 数据流章节

**内容**
- 构造函数注入 `ToolRegistry`、`ToolExecutor`、`List<Tool>`（传给 LLMProvider）
- `buildApiMessages()` 不变（已在任务 2 中支持 toolCall/toolResult 字段）
- `streamResponse()` 改为三步：
  1. 调用 `provider.streamChat(messages, tools, callback)` 获取模型回复
  2. 若回调收到 `onToolCall`：组装 assistant 消息（含 tool_calls）加入历史 → 从 ToolRegistry 取工具 → `toolExecutor.execute(tool, toolCallId, params)` → 组装 user 消息（含 tool_result）加入历史 → 再次 `streamChat()` 获取最终文本回复
  3. 若第二轮模型又返回 tool_call → 不再执行，附一条 "本轮仅支持一次工具调用" 的系统提示
- 注意：确认逻辑不在 DialogueManager 层，ExecuteCommandTool 内部已通过确认回调处理

---

### 任务 12 · 接入主流程（Main.java 装配）

**影响文件**
- `src/main/java/com/bingtangcode/Main.java`
- `src/main/java/com/bingtangcode/config/ConfigManager.java`（新增 toolTimeout 配置项）
- `config.example.yaml`（新增 tool.timeout_seconds 配置）

**依赖**: 任务 3、任务 4、任务 5、任务 11

**内容**
- Main.java 中创建 ToolRegistry → 手动 register 六个工具（ExecuteCommandTool 注入 `terminalIO::confirmCommand`，所有工具注入 projectRoot） → 创建 ToolExecutor → 注入 DialogueManager
- ConfigManager 增加 `getToolTimeoutSeconds()`，默认 30
- `config.example.yaml` 增加 `tool.timeout_seconds: 30`
- 启动时打印已注册工具数量

---

### 任务 13 · 端到端验证

**影响文件**: 无（手工测试）

**依赖**: 任务 12

**内容**
- 启动 bingtangCode，逐一验证六个工具
- 验证 edit_file 的匹配失败和多次匹配错误提示
- 验证 execute_command 确认对话框弹出与拒绝/确认两种路径
- 验证工具超时（构造超过 30s 的命令）后模型收到错误并给出说明
- 验证模型调用工具后拿到结果并生成最终文本回复（完整链路）
