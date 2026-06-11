# checklist.md · Agent Loop（ReAct 自主循环）

## Tool 接口 — isReadOnly

- [ ] `grep "default boolean isReadOnly()" src/main/java/com/bingtangcode/tool/Tool.java` 返回结果
- [ ] `grep "isReadOnly.*true" src/main/java/com/bingtangcode/tool/tools/ReadFileTool.java` 返回结果
- [ ] `grep "isReadOnly.*true" src/main/java/com/bingtangcode/tool/tools/FindFilesTool.java` 返回结果
- [ ] `grep "isReadOnly.*true" src/main/java/com/bingtangcode/tool/tools/SearchContentTool.java` 返回结果
- [ ] WriteFileTool、EditFileTool、ExecuteCommandTool 不覆盖 isReadOnly（`grep "isReadOnly"` 在三个文件中均不返回结果）

## EventBus 事件体系

- [ ] `grep "record TextDelta\|class TextDelta" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record ReasoningDelta\|class ReasoningDelta" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record ToolCallStarted\|class ToolCallStarted" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record ToolCallCompleted\|class ToolCallCompleted" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record TokenUsage\|class TokenUsage" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record LoopIterationStarted\|class LoopIterationStarted" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record LoopIterationEnded\|class LoopIterationEnded" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `grep "record AgentFinished\|class AgentFinished" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回结果
- [ ] `AgentEvent` 共 8 种事件类型（`grep "record.*Event\|sealed.*Event" src/main/java/com/bingtangcode/agent/AgentEvent.java` 返回 ≥8 条）
- [ ] `grep "interface AgentEventListener" src/main/java/com/bingtangcode/agent/AgentEventListener.java` 返回结果
- [ ] AgentEventListener 每个 `on*` 方法均有 `default {}` 空实现（`grep "default" src/main/java/com/bingtangcode/agent/AgentEventListener.java` 返回 ≥8 条）
- [ ] `grep "class EventBus" src/main/java/com/bingtangcode/agent/EventBus.java` 返回结果
- [ ] EventBus 内部用 `CopyOnWriteArrayList`（`grep "CopyOnWriteArrayList" src/main/java/com/bingtangcode/agent/EventBus.java` 返回结果）
- [ ] EventBus.fire() 中捕获监听器异常不向上抛（源码审查：catch(Exception) 包裹每个 listener 调用）

## ConfigManager — maxIterations

- [ ] `grep "max_iterations\|maxIterations" src/main/java/com/bingtangcode/config/ConfigManager.java` 返回结果
- [ ] `grep "maxIterations\|max_iterations" config.example.yaml` 返回结果
- [ ] ConfigManager.getMaxIterations() 默认返回 20（不配置时）

## DialogueManager — doRound()

- [ ] `grep "doRound" src/main/java/com/bingtangcode/core/DialogueManager.java` 返回结果
- [ ] `grep "class RoundResult\|record RoundResult" src/main/java/com/bingtangcode/core/RoundResult.java` 返回结果
- [ ] doRound 方法签名包含 `EventBus` 参数（源码审查）
- [ ] doRound 内部用 `CountDownLatch` 等待异步 streamChat（源码审查）
- [ ] doRound 内部 fire TextDelta / ReasoningDelta 事件（源码审查：eventBus.fire 在 onToken/onReasoning 回调中）
- [ ] doRound 内部 fire TokenUsage 事件（源码审查：eventBus.fire 在 onUsage 回调中）
- [ ] doRound 内部 fire ToolCallStarted / ToolCallCompleted 事件（源码审查：工具执行前后）
- [ ] doRound 无 toolCall 时返回 `RoundResult.COMPLETED`（源码审查）
- [ ] doRound 有 toolCall 时执行完工具后返回非 COMPLETED 的 RoundResult（源码审查）
- [ ] 原有的 `streamResponse()` 方法保持不变（向后兼容）

## 多工具分批执行（doRound 内部）

- [ ] 只读工具（read_file、find_files、search_content）并发执行（通过时间戳观察）
- [ ] 副作用工具（write_file、edit_file、execute_command）串行执行（通过时间戳观察）
- [ ] 只读批次全部完成后再开始副作用批次（通过时间戳观察）
- [ ] tool_result 消息中结果顺序与原始 tool_call 声明顺序一致

## AgentLoop — 薄循环层

- [ ] `grep "class AgentLoop" src/main/java/com/bingtangcode/agent/AgentLoop.java` 返回结果
- [ ] AgentLoop 构造函数接收 DialogueManager、ToolRegistry、EventBus、maxIterations（源码审查，不依赖 ToolExecutor/LLMProvider 直接引用）
- [ ] AgentLoop 循环体内调用 `dialogue.doRound()` 而非 `provider.streamChat()`（源码审查）
- [ ] AgentLoop **不** import `ToolCall`（`grep "import.*ToolCall" src/main/java/com/bingtangcode/agent/AgentLoop.java` 无结果）
- [ ] AgentLoop **不** import `ToolResult`（`grep "import.*ToolResult" src/main/java/com/bingtangcode/agent/AgentLoop.java` 无结果）
- [ ] AgentLoop **不** import `Message`（`grep "import.*llm.Message" src/main/java/com/bingtangcode/agent/AgentLoop.java` 无结果）
- [ ] AgentLoop **不** import `StreamCallback`（`grep "import.*StreamCallback" src/main/java/com/bingtangcode/agent/AgentLoop.java` 无结果）
- [ ] 循环体在 while 中检查 iteration < maxIterations（源码审查）

## 停止条件

- [ ] 模型返回无 tool_call（RoundResult.completed=true）→ AgentFinished.stopReason = "COMPLETED"
- [ ] 循环轮次达到 maxIterations（默认 20）→ AgentFinished.stopReason = "MAX_ITERATIONS"
- [ ] 用户按 Ctrl+C → AgentFinished.stopReason = "CANCELLED"
- [ ] 连续 3 次调到未知工具 → AgentFinished.stopReason = "UNKNOWN_TOOL_LOOP"
- [ ] 未知工具第 1 次出现：doRound 返回 hasUnknown=true，error tool_result 回灌，不终止
- [ ] 未知工具第 2 次出现：doRound 返回 hasUnknown=true，error tool_result 回灌，不终止
- [ ] 未知工具第 3 次出现：AgentLoop 终止循环（UNKNOWN_TOOL_LOOP）
- [ ] 中途调到已知工具：unknownToolStreak 重置为 0

## 流式异常重试

- [ ] API 异常第 1 次：等待 1s 后重试 doRound()
- [ ] API 异常第 2 次：等待 2s 后重试 doRound()
- [ ] API 异常第 3 次：等待 4s 后重试 doRound()
- [ ] 3 次全部失败 → AgentFinished.stopReason = "STREAM_ERROR"
- [ ] 任意一次重试成功 → 正常继续，retryCount 重置
- [ ] Ctrl+C 立即终止重试循环（不继续退避等待）

## 事件职责分界

- [ ] TextDelta 由 DialogueManager.doRound() 发送（源码审查：eventBus.fire(TextDelta) 在 doRound 内部）
- [ ] ReasoningDelta 由 DialogueManager.doRound() 发送（源码审查）
- [ ] ToolCallStarted / ToolCallCompleted 由 DialogueManager.doRound() 发送（源码审查）
- [ ] TokenUsage 由 DialogueManager.doRound() 发送（源码审查）
- [ ] LoopIterationStarted / LoopIterationEnded 由 AgentLoop 发送（源码审查）
- [ ] AgentFinished 由 AgentLoop 发送（源码审查）

## Token 用量

- [ ] TokenUsage.inputTokens = 本轮 LLM 返回的 input_tokens
- [ ] TokenUsage.outputTokens = 本轮 LLM 返回的 output_tokens
- [ ] TokenUsage.totalInput = 从 AgentLoop 启动到现在的累计 input
- [ ] TokenUsage.totalOutput = 从 AgentLoop 启动到现在的累计 output

## Plan Mode

- [ ] 输入 `/plan` 后终端显示 "已切换到 Plan Mode，仅可用只读工具"
- [ ] Plan Mode 下 selectTools() 只返回 ReadFileTool、FindFilesTool、SearchContentTool
- [ ] Plan Mode 下让模型做修改性任务，模型应无法调 write_file/edit_file/execute_command
- [ ] 输入 `/do` 后终端显示 "已切换到 Do Mode，全工具可用"
- [ ] Do Mode 下 selectTools() 返回全部 6 个工具
- [ ] /plan 期间的对话历史在 /do 后完整保留

## Reasoning

- [ ] LLMProvider.streamChat 返回的 StreamCallback.onReasoning 在 Anthropic thinking 事件下正常回调（若 API 支持）
- [ ] LLMProvider.streamChat 返回的 StreamCallback.onReasoning 在 OpenAI reasoning 下正常回调（若 API 支持）
- [ ] 纯文本模型（无 reasoning）不回调 onReasoning 时不报错

## 主流程装配

- [ ] `grep "AgentLoop" src/main/java/com/bingtangcode/Main.java` 返回结果
- [ ] `grep "EventBus" src/main/java/com/bingtangcode/Main.java` 返回结果
- [ ] `grep "TuiEventListener" src/main/java/com/bingtangcode/Main.java` 返回结果
- [ ] SessionManager 构造函数只注入 AgentLoop + cancelAction（不直接注入 DialogueManager + LLMProvider）
- [ ] `mvn clean compile` 返回 BUILD SUCCESS

## 端到端验证

- [ ] 启动 bingtangCode，输入 "你好"，模型直接回复文本，不调工具，AgentFinished 原因为 COMPLETED
- [ ] 输入 "帮我读一下 pom.xml"，模型调 read_file → 结果回灌 → 模型总结 → 自然结束
- [ ] 输入 "帮我搜一下项目里所有提到 Anthropic 的文件，然后读第一个文件"，模型至少经历 2 轮循环并最终给出结果
- [ ] 构造一个模型无法完成的任务（连续 20 轮），观察 Agent 在 20 轮后自动停止，提示迭代上限
- [ ] 任务执行中按 Ctrl+C，Agent 立即停止，终端输出取消提示
- [ ] /plan 后模型只能做调研，/do 后模型能修改文件
