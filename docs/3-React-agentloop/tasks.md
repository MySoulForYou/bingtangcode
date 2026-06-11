# tasks.md · Agent Loop（ReAct 自主循环）

## 概述

12 个任务，按依赖顺序排列。每个任务标注影响文件、依赖和参考定位。

**设计要点**（实现时遵守）：
- AgentLoop 是一层薄循环：只做 while + 选择工具集 + 停止判断 + 发循环级事件
- DialogueManager 新增 `doRound()` 方法，封装 LLM 调用→流式收集→工具执行→写历史，内部用 CountDownLatch 同步等待
- AgentLoop 通过 `dialogue.doRound()` 获取 RoundResult，不直接拼 Message、不直接调 ToolExecutor
- 流式事件（TextDelta/ReasoningDelta）和工具事件（ToolCallStarted/ToolCallCompleted）由 DialogueManager 通过 EventBus 发出
- 循环级事件（LoopIterationStarted/LoopIterationEnded/AgentFinished）由 AgentLoop 发出
- `Tool.isReadOnly()` 由各工具自行声明，DialogueManager 据此分组并发/串行
- Plan Mode 通过 AgentLoop 内部 mode 字段切换可见工具集，/plan / /do 命令在 SessionManager 层解析

---

### 任务 1 · Tool 接口增加 isReadOnly() 方法

**影响文件**
- `src/main/java/com/bingtangcode/tool/Tool.java`
- `src/main/java/com/bingtangcode/tool/tools/ReadFileTool.java`
- `src/main/java/com/bingtangcode/tool/tools/WriteFileTool.java`
- `src/main/java/com/bingtangcode/tool/tools/EditFileTool.java`
- `src/main/java/com/bingtangcode/tool/tools/ExecuteCommandTool.java`
- `src/main/java/com/bingtangcode/tool/tools/FindFilesTool.java`
- `src/main/java/com/bingtangcode/tool/tools/SearchContentTool.java`

**依赖**: 无（纯接口扩展，不改已有方法签名）

**内容**
- `Tool` 接口新增 `default boolean isReadOnly() { return false; }`
- `ReadFileTool`、`FindFilesTool`、`SearchContentTool` override 返回 `true`
- 其余三个工具（WriteFileTool、EditFileTool、ExecuteCommandTool）不 override，继承默认 false

**验证参考**: spec.md Tool 接口

---

### 任务 2 · 定义 AgentEvent 体系和 EventBus

**影响文件**
- `src/main/java/com/bingtangcode/agent/AgentEvent.java` — 事件类型 + 数据载体
- `src/main/java/com/bingtangcode/agent/AgentEventListener.java` — 监听器接口
- `src/main/java/com/bingtangcode/agent/EventBus.java` — 事件总线

**依赖**: 无

**内容**
- `AgentEvent` 用 sealed interface + record 定义 8 种事件类型及其携带数据（见 spec.md AgentEvent 体系表）
- `AgentEventListener` 接口：每种事件一个 `on*` 方法，全部带 `default {}` 空实现（订阅者只 override 感兴趣的）
- `EventBus`：`subscribe(AgentEventListener)`、`unsubscribe(AgentEventListener)`、`fire(AgentEvent)`；内部用 `CopyOnWriteArrayList<AgentEventListener>` 保证线程安全；`fire()` 同步调用所有监听器（异常被捕获不中断循环）

**参考**: spec.md AgentEvent 体系表

---

### 任务 3 · ConfigManager 增加 maxIterations 配置项

**影响文件**
- `src/main/java/com/bingtangcode/config/ConfigManager.java`
- 项目根目录 `config.example.yaml`

**依赖**: 无

**内容**
- ConfigManager 新增常量 `DEFAULT_MAX_ITERATIONS = 20`
- 构造函数中读取配置项路径：`agent.max_iterations`（在 root 下取 `agent` map）
- 新增 `getMaxIterations()` 方法
- `config.example.yaml` 增加：
  ```yaml
  agent:
    max_iterations: 20
  ```

**参考**: `ConfigManager.java:62`（tool.timeout_seconds 类似模式）

---

### 任务 4 · DialogueManager 新增 doRound() 同步方法

**影响文件**
- `src/main/java/com/bingtangcode/core/DialogueManager.java`
- 新增 `src/main/java/com/bingtangcode/core/RoundResult.java`

**依赖**: 任务 1（isReadOnly）、任务 2（EventBus）

**内容**

- 新建 `RoundResult` record：`boolean completed`, `List<ToolCall> toolCalls`, `String textContent`, `boolean hasUnknown`，含静态常量 `RoundResult COMPLETED`
- DialogueManager 新增 `doRound(List<Tool> tools, EventBus eventBus, AtomicInteger totalInput, AtomicInteger totalOutput)` 方法
- doRound 内部流程：
  1. `buildApiMessages()` 清洗对话历史
  2. 构造 StreamCallback：
     - `onToken` → `eventBus.fire(TextDelta)` + 积攒到 textBuilder
     - `onReasoning` → `eventBus.fire(ReasoningDelta)` + 积攒到 reasoningBuilder
     - `onToolCall` → 收集到 pendingToolCalls
     - `onUsage(inputTokens, outputTokens)` → 更新累计 + `eventBus.fire(TokenUsage)`（带本轮和累计值）
     - `onComplete` → countDown latch
     - `onError` → 记录异常 + countDown latch
  3. `provider.streamChat(messages, tools, callback)` 异步提交
  4. `latch.await()` 阻塞等待（provider 线程回调 onComplete/onError 时唤醒）
  5. 如果 onError 异常 → 抛出给调用方（AgentLoop 重试）
  6. 如果 pendingToolCalls 为空 → 写入 `assistant(text)` 到 history → 返回 `RoundResult.COMPLETED`
  7. 如果 pendingToolCalls 不为空：
     - 检查 `toolRegistry.get(name)` → 有任一工具未知则 `hasUnknown=true`，向所有未知工具构造 error ToolResult
     - 写入 `assistant(textPrefix, toolCalls)` 到 history
     - 按 `isReadOnly()` 分组执行工具（见任务 5）
     - 写入 `user(toolResults)` 到 history
     - 返回 `new RoundResult(false, toolCalls, textContent, hasUnknown)`
- 方法签名抛异常，异常由 AgentLoop 的 retry 逻辑处理
- 保留原有的 `streamResponse()` 方法不变（向后兼容，SessionManager 不再用它但它可能被其他地方使用）

**参考**:
- `DialogueManager.java:55` 当前 doStream 逻辑
- `DialogueManager.java:108` 当前 handleToolCalls 逻辑
- `DialogueManager.java:159` buildApiMessages
- spec.md doRound() 伪代码

---

### 任务 5 · 多工具分批并发执行（doRound 内部）

**影响文件**
- `src/main/java/com/bingtangcode/core/DialogueManager.java`（doRound 内部方法）

**依赖**: 任务 4

**内容**
- `executeToolBatch(List<ToolCall> toolCalls, EventBus eventBus)` private 方法
- 按 `Tool.isReadOnly()` 分为两组：
  - `List<ToolCall> readOnly` — 用固定线程池并发提交
  - `List<ToolCall> mutation` — 单线程逐个执行，保持声明顺序
- 每个工具执行前后：`eventBus.fire(ToolCallStarted)` / `eventBus.fire(ToolCallCompleted)`（携带 toolName, toolCallId, isError, elapsedMs）
- 只读组先全部完成 → 再执行副作用组 → 全部结束后返回按原始顺序排列的 `List<ToolResult>`
- 工具执行复用已有的 `ToolExecutor.execute()`（含超时控制）

**参考**: spec.md 多工具分批执行策略图、`ToolExecutor.java:34` execute 方法

---

### 任务 6 · LLMProvider 接口增加 Token Usage 回传

**影响文件**
- `src/main/java/com/bingtangcode/llm/StreamCallback.java`
- `src/main/java/com/bingtangcode/llm/LLMimpl/AnthropicProvider.java`
- `src/main/java/com/bingtangcode/llm/LLMimpl/OpenAIProvider.java`

**依赖**: 无（独立改动）

**内容**
- `StreamCallback` 新增 `default void onUsage(int inputTokens, int outputTokens) {}`
- AnthropicProvider 在 `message_start` 事件中提取 `message.usage.input_tokens`，在 `message_delta` 中提取 `usage.output_tokens`，在 `message_stop` 或流结束前回调 `onUsage`
- OpenAIProvider 在 `usage` 字段出现时提取 `prompt_tokens` + `completion_tokens`，回调 `onUsage`
- 如果 API 响应不包含 usage 信息，不回调（保持向后兼容）

**参考**:
- `AnthropicProvider.java:246-247` content_block_start 解析
- `AnthropicProvider.java:270-271` message_delta 解析
- `OpenAIProvider.java:184-298` parseSSEStream

---

### 任务 7 · 扩展 StreamCallback 支持推理/思考事件

**影响文件**
- `src/main/java/com/bingtangcode/llm/StreamCallback.java`
- `src/main/java/com/bingtangcode/llm/LLMimpl/AnthropicProvider.java`（支持 thinking 事件）
- `src/main/java/com/bingtangcode/llm/LLMimpl/OpenAIProvider.java`（支持 reasoning 事件）

**依赖**: 无

**内容**
- `StreamCallback` 新增 `default void onReasoning(String token) {}`
- AnthropicProvider：SSE 解析中识别 `content_block_start` type 为 `"thinking"` / `"redacted_thinking"`，以及 `content_block_delta` delta type 为 `"thinking_delta"` / `"signature_delta"`，解析后回调 `onReasoning`
- OpenAIProvider：SSE 解析中识别 `delta.reasoning_content`，回调 `onReasoning`（替代当前直接 System.out.print 的做法）
- 两个 Provider 遇到未知/不支持的思考事件类型时静默忽略

---

### 任务 8 · AgentLoop 增加 Plan Mode（/plan / /do）

**影响文件**
- `src/main/java/com/bingtangcode/agent/AgentLoop.java`

**依赖**: 任务 4（doRound 已就绪）

**内容**
- `AgentLoop` 新增 `enum Mode { PLAN, FULL }` 和 `Mode mode` 字段，初始为 `FULL`
- `setMode(Mode mode)` / `getMode()` 方法
- 循环体内 `selectTools()` 方法：PLAN 模式从 toolRegistry.getAll() 过滤出 `isReadOnly()==true` 的工具；FULL 模式返回全部
- 过滤后的工具列表作为参数传给 `dialogue.doRound(tools, ...)`
- 模式切换发生在 Agent Loop 空闲时（两轮对话之间），正在执行中的循环不受影响

---

### 任务 9 · 实现 AgentLoop 核心循环（薄循环层）

**影响文件**
- `src/main/java/com/bingtangcode/agent/AgentLoop.java`

**依赖**: 任务 4（doRound）、任务 3（maxIterations）、任务 8（Plan Mode）

**内容**
- 构造函数：`AgentLoop(DialogueManager dialogue, LLMProvider provider, ToolRegistry registry, EventBus bus, int maxIterations)`
  - 注：provider 和 registry 用于传递给 doRound（或通过构造函数注入 DialogueManager）
- `run(String userInput)` 方法：
  1. `dialogue.addUserMessage(userInput)`
  2. 初始化累计 token 计数器（`AtomicInteger totalInput = new AtomicInteger(0)`, `AtomicInteger totalOutput = new AtomicInteger(0)`）
  3. while (iteration < maxIterations):
     - 检查 `cancelled` → 发 `AgentFinished(CANCELLED)` → return
     - `bus.fire(LoopIterationStarted(iteration))`
     - 调用 `dialogue.doRound(selectTools(), bus, totalInput, totalOutput)` 包裹在 retry 循环中（指数退避 1s/2s/4s）
     - 如果 retry 耗尽 → 发 `AgentFinished(STREAM_ERROR)` → return
     - `bus.fire(LoopIterationEnded(iteration, elapsed))`
     - 如果 `result.completed()` → 发 `AgentFinished(COMPLETED)` → return
     - 如果 `result.hasUnknown()` → unknownToolStreak++；如果 ≥3 → 发 `AgentFinished(UNKNOWN_TOOL_LOOP)` → return；否则 continue
     - 正常：`unknownToolStreak = 0` → continue 下一轮
  4. 循环溢出 → 发 `AgentFinished(MAX_ITERATIONS)`
- `cancel()` 方法供 SessionManager 绑定 Ctrl+C
- **不直接** import `ToolCall`、`ToolResult`、`Message`、`StreamCallback`（通过 doRound 间接使用）

**参考**:
- `DialogueManager.java:159` buildApiMessages 方法签名
- `SessionManager.java:68` cancelAction 绑定模式
- spec.md Agent Loop 停止条件流程

---

### 任务 10 · SessionManager 解析 /plan / /do 命令

**影响文件**
- `src/main/java/com/bingtangcode/core/SessionManager.java`

**依赖**: 任务 9

**内容**
- SessionManager 构造函数注入 `AgentLoop`（替代原来直接依赖 DialogueManager + LLMProvider）
- `readLine` 循环中识别 `/plan` 和 `/do` 命令：
  - `/plan`：调用 `agentLoop.setMode(PLAN)`，打印提示 "已切换到 Plan Mode，仅可用只读工具"
  - `/do`：调用 `agentLoop.setMode(FULL)`，打印提示 "已切换到 Do Mode，全工具可用"
- 其他输入交由 `agentLoop.run(input)` 处理（同步阻塞直到 Agent 完成）
- Ctrl+C 绑到 `agentLoop.cancel()` + provider cancelAction
- 不再使用 CountDownLatch 等待（agentLoop.run() 内部同步等待）

**参考**:
- `SessionManager.java:37` start() 主循环
- `SessionManager.java:53` /exit 命令处理模式
- `SessionManager.java:68` interruptHandler

---

### 任务 11 · 接入主流程（Main.java 装配）

**影响文件**
- `src/main/java/com/bingtangcode/Main.java`
- 新增 `src/main/java/com/bingtangcode/tui/TuiEventListener.java`

**依赖**: 任务 1~任务 10

**内容**
- Main.java 中创建 EventBus 实例
- 实例化 AgentLoop，注入 DialogueManager、LLMProvider、ToolRegistry、EventBus、maxIterations
- 注册界面监听器：`eventBus.subscribe(new TuiEventListener(terminalIO))` —— TuiEventListener 负责将事件翻译为终端打印（TextDelta→逐字打印、ReasoningDelta→灰色打印、ToolCallCompleted→工具进度✓✗、AgentFinished→结束提示）
- SessionManager 注入 AgentLoop + cancelAction（不再注入 DialogueManager + provider）
- `mvn clean compile` 通过

**TuiEventListener 实现**
- 实现 `AgentEventListener` 接口
- `onTextDelta` → `terminalIO.printToken(token)`
- `onReasoningDelta` → `System.out.print(gray + token + reset)`
- `onToolCallCompleted` → 打印 `工具名 ✓/✗ 耗时ms`
- `onAgentFinished` → 处理 CANCELLED / MAX_ITERATIONS 提示

---

### 任务 12 · 端到端验证

**影响文件**: 无（手工测试）

**依赖**: 任务 11

**内容**
- 启动 bingtangCode，验证以下场景：
  1. 纯文本问答正常（无工具调用）——模型直接回复，AgentFinished 原因为 COMPLETED
  2. 单工具往返正常——如 "读一下 pom.xml" → 模型调 read_file → 拿到结果 → 模型总结 → 结束
  3. 多工具连环调用——如 "帮我搜一下所有提到 Anthropic 的 Java 文件，然后读第一个" → 两轮循环自动完成
  4. 多工具并发 —— 一次返回多个 read_file/serach_content，确认它们并发执行（观察进度时间戳）
  5. 副作用串行 —— 一次返回 write_file + execute_command，确认串行执行
  6. 迭代上限触发 —— 构造一个无论如何也无法完成的任务，观察 20 轮后自动停止，AgentFinished 原因为 MAX_ITERATIONS
  7. Ctrl+C 取消 —— 任务执行中按 Ctrl+C，AgentFinished 原因为 CANCELLED
  8. 未知工具终止 —— 模拟注册表中缺工具，被连续调用 3 次后终止，UNKNOWN_TOOL_LOOP
  9. /plan 模式 —— 输入 /plan，确认只读工具可用；让模型做调研，确认它只能读不能写
  10. /do 切回 —— 在 /plan 后再 /do，确认全工具恢复可用
