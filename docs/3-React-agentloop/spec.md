# spec.md · Agent Loop（ReAct 自主循环）

## 背景

bingtangCode 当前完成了一次工具调用往返：模型调一个工具 → 拿到结果 → 生成文本回复。但模型不会自动继续——用户必须手动催促下一步。这不是 Agent。

本章给 bingtangCode 装上 Agent Loop：ReAct 模式的思考-行动-观察循环。模型自主决定何时调工具、何时总结、何时继续，直到任务真正完成。

## 目标用户

在终端里用 bingtangCode 做编程任务的开发者——他们给一个任务预期就能看到 Agent 自己循环推进直至完成。

## 能力清单

1. **ReAct 循环** — AgentLoop 循环调用 `dialogue.doRound()`，由 DialogueManager 内部完成 LLM 调用→收集→工具执行→写历史，AgentLoop 只负责 while 循环和停止判断
2. **五种停止条件** — 模型自然说完（无 tool_call）、迭代次数触达上限（兜底安全网）、用户 Ctrl+C 取消、连续 3 次调到未知工具、流式传输 API 异常重试 3 次后仍失败
3. **事件总线解耦** — 定义 AgentEvent 体系（TextDelta / ReasoningDelta / ToolCallStarted / ToolCallCompleted / TokenUsage / LoopIterationStarted / LoopIterationEnded / AgentFinished），DialogueManager 发流式/工具事件，AgentLoop 发循环级事件，界面或任何订阅者收事件，完全解耦
4. **DialogueManager.doRound()** — DialogueManager 新增同步方法 `doRound(tools, eventBus)`：内部建 StreamCallback 做流式双路收集（一边推 EventBus 一边积攒），完成后如果有工具调用则按 isReadOnly 分批执行并写入历史，返回 RoundResult（completed / toolCalls / textContent / hasUnknown）给 AgentLoop 决策
5. **多工具安全分批执行** — 模型一次返回多个 tool_call 时，按工具自声明的 isReadOnly() 分组：只读工具全并发跑，有副作用的工具逐个串行跑；上一批全完成后再跑下一批；全部完成后一次性回灌所有 tool_result
6. **Token 用量追踪** — 每次 LLM 调用后发出 TokenUsage 事件（同时包含本轮用量和累计用量），界面可据此展示
7. **Plan Mode 两段式** — AgentLoop 内部 mode 字段控制当前可见工具集：`/plan` 切为 read-only 工具（read_file、find_files、search_content），让模型只做调研和计划；`/do` 切回全量工具开始执行。两段共享对话历史，模型在 /do 时直接可见 /plan 期间产出的计划
8. **迭代上限可配置** — ConfigManager 新增 maxIterations 配置项，默认 20，可在 config.yaml 中覆盖
9. **指数退避重试** — API 流式异常按 1s / 2s / 4s 间隔退避，最多重试 3 次，全失败则终止循环
10. **Ctrl+C 终止整个循环** — 用户取消后不再继续当前迭代，直接发出 AgentFinished（停止原因为 CANCELLED）并结束

## Out of Scope

- 权限系统（不是"能不能调工具"，所有注册的工具都能调）
- 上下文压缩（上下文超长时不做任何截断或摘要）
- 用户交互式确认（execute_command 的确认对话框仍保留，但不新增其他确认）
- Plan Mode 产出的计划格式校验、计划质量评估
- 跨会话持久化（Agent Loop 状态不落盘）
- 并发工具之间的结果传递（工具之间不依赖彼此的输出）
- 工具执行沙箱
- MCP 协议集成

## 非功能要求

- Agent Loop 不能阻塞终端 UI 响应（事件总线异步通知界面）
- 事件监听器的注册/移除线程安全（CopyOnWriteArrayList）
- 每轮迭代上限耗时可观测（LoopIterationEnded 携带本轮耗时）
- `isReadOnly()` 声明必须准确——如果只读工具被错误标为有副作用，仅损失并发性能；如果有副作用工具被标为只读，可能导致数据竞争
- AgentLoop 不依赖具体 Tool/Message/StreamCallback 细节，只通过 `dialogue.doRound()` 和 EventBus 接口交互

## 设计骨架

```
┌─────────────────────────────────────────────────────────────────┐
│                       SessionManager                            │
│  start() 主循环: 读用户输入 → /plan|/do|/exit → agentLoop.run()  │
└─────────────────────┬───────────────────────────────────────────┘
                      │
         ┌────────────▼────────────┐
         │     AgentLoop           │  ← 薄循环层
         │  mode: PLAN / FULL      │
         │  maxIterations: 20      │
         │  ┌───────────────────┐  │
         │  │  while (继续) {    │  │
         │  │    ① 选择工具集     │  │
         │  │    ② doRound() ────│──┼──→ DialogueManager
         │  │    ③ 拿到结果:      │  │       │
         │  │       完成? 终止    │  │       │  buildApiMessages()
         │  │       未知? 纠偏    │  │       │  streamChat + 收集
         │  │       正常? 继续    │  │       │  有工具? 分批执行
         │  │    ④ 发循环级事件   │  │       │  写 history
         │  │  }                 │  │       │  返回 RoundResult
         │  └───────────────────┘  │  ←── 返回
         │  发事件 → EventBus       │
         └────┬────────────────────┘
              │
    ┌─────────▼──────────┐
    │     EventBus       │
    │  fire(event)       │
    │  subscribe()       │
    └──┬──────────┬──────┘
       │          │
  ┌────▼────┐ ┌──▼──────────┐
  │TuiEvent │ │ 其他订阅者   │
  │Listener │ │ (日志/测试)  │
  └─────────┘ └─────────────┘
```

### AgentEvent 体系

| 事件类型 | 携带数据 | 触发时机 | 发送者 |
|---------|---------|---------|-------|
| `LoopIterationStarted` | iteration (第几轮) | 每轮循环开始 | AgentLoop |
| `ReasoningDelta` | token 文本 | 模型输出推理/思考内容时 | DialogueManager |
| `TextDelta` | token 文本 | 模型输出可见文本时 | DialogueManager |
| `ToolCallStarted` | toolName, toolCallId | 开始执行一个工具 | DialogueManager |
| `ToolCallCompleted` | toolName, toolCallId, isError, elapsedMs | 一个工具执行完毕 | DialogueManager |
| `TokenUsage` | inputTokens本轮, outputTokens本轮, totalInput累计, totalOutput累计 | LLM 请求完成后 | DialogueManager |
| `LoopIterationEnded` | iteration, elapsedMs | 每轮循环结束（doRound 返回后） | AgentLoop |
| `AgentFinished` | stopReason (COMPLETED / MAX_ITERATIONS / CANCELLED / UNKNOWN_TOOL_LOOP / STREAM_ERROR) | 整个 Agent Loop 终止 | AgentLoop |

### DialogueManager 新增 doRound() 方法

```java
/**
 * 同步执行一轮 LLM 请求 + 可选的工具执行。
 * 内部用 CountDownLatch 等待异步 streamChat 完成。
 *
 * @param tools     当前可用的工具列表（由 AgentLoop 根据 mode 过滤后传入）
 * @param eventBus  事件总线，用于推流式/工具事件
 * @param totalInputTokens  累计 input tokens（会被更新）
 * @param totalOutputTokens 累计 output tokens（会被更新）
 * @return RoundResult 包含本轮是否完成、工具调用列表、文本内容、是否含有未知工具
 */
public RoundResult doRound(List<Tool> tools, EventBus eventBus,
                           AtomicInteger totalInputTokens, AtomicInteger totalOutputTokens);
```

内部流程：
1. `buildApiMessages()` 清洗对话历史
2. 构造 StreamCallback：onToken → fire(TextDelta) + 积攒；onReasoning → fire(ReasoningDelta) + 积攒；onToolCall → 收集；onUsage → fire(TokenUsage) + 更新累计；onComplete → countDown latch
3. `provider.streamChat(messages, tools, callback)` 异步提交
4. `latch.await()` 阻塞等待
5. 如果无 toolCall：写入 assistant 消息到 history，返回 `RoundResult.COMPLETED`
6. 如果有 toolCall：检查未知工具 → 写入 assistant(toolCalls) → 按 isReadOnly 分批执行工具（只读并发、副作用串行）→ 写入 user(toolResults) → 返回 RoundResult

### RoundResult

```java
record RoundResult(boolean completed, List<ToolCall> toolCalls,
                   String textContent, boolean hasUnknown) {
    static final RoundResult COMPLETED = new RoundResult(true, List.of(), "", false);
}
```

### 多工具分批执行策略

```
模型返回 5 个 tool_call: [read_file:A, write_file:B, read_file:C, execute_command:D, search_content:E]
                                    ↓
                        按 isReadOnly() 分组
                    ╱                           ╲
        只读组 (并发):                     副作用组 (串行):
        A  ─┬─ C  ─┬─ E                  B ──→ D
           │      │                     (等 B 完成再跑 D)
           └─并发─┘
              ↓
        全部完成，一次性回灌所有 tool_result
              ↓
        下一轮 LLM 调用
```

### Tool 接口

```java
// 新增方法
default boolean isReadOnly() { return false; }
// 只读工具覆盖返回 true: read_file, find_files, search_content
```

### Agent Loop 停止条件流程

```
while (iteration < maxIterations) {
    try {
        result ← dialogue.doRound(tools, eventBus, ...)
        if (result.completed)  →  COMPLETED    ✓
        if (result.hasUnknown) →
            unknownToolStreak++
            if (unknownToolStreak >= 3) → UNKNOWN_TOOL_LOOP  ✗
            否则 → 模型纠偏 → 继续
        // 正常工具执行 → 继续下一轮
    } catch (StreamException e) {
        retryCount++
        if (retryCount >= 3) →  STREAM_ERROR  ✗
        否则 sleep(2^(retryCount-1)s) → 重试
    }
    if (用户 Ctrl+C) → CANCELLED  ✗
}
循环溢出 →  MAX_ITERATIONS  ✗
```
