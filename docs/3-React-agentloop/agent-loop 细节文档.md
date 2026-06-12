# flow-guide.md · bingtangCode 完整流程指南

面向小白。读完你能理解：bingtangCode 是什么、怎么跑的、每个部分负责什么、遇到边界情况怎么处理。

---

## 一、bingtangCode 能做什么

bingtangCode 是一个**终端 AI 编程助手**。你告诉它一句话，它自己思考、自己调工具、自己分析结果，循环推进直到任务完成——你不需要手动催它下一步。

举个例子：

```
> 帮我搜一下项目里所有提到 Anthropic 的 Java 文件，然后读第一个

Agent 内部自动完成:
  第1轮: 调 search_content("Anthropic") → 找到 3 个文件
  第2轮: 调 read_file("AnthropicProvider.java") → 拿到文件内容
  第3轮: 模型看完文件，总结回复 → "找到了 3 个文件，第一个是..."

你只发了一条指令，Agent 自己跑了 3 轮。
```

### 不做什么

| 能做 | 不能做 |
|------|--------|
| 读/写/编辑文件 | 权限控制（所有工具都能调） |
| 执行 shell 命令 | 命令沙箱 |
| 搜索代码内容/文件名 | 跨会话记忆 |
| 多轮自主循环（最多 20 轮） | 上下文超长时自动压缩 |
| Plan Mode 先调研再执行 | 并发工具之间传数据 |

---

## 二、项目架构

```
┌────────────────────────────────────────────────────────┐
│                     Main.java                          │
│              启动时组装所有零件，接线                     │
└──────────────────────┬─────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
┌───────────┐  ┌─────────────┐  ┌──────────┐
│SessionMgr │  │  AgentLoop  │  │ EventBus │
│读输入     │  │ while循环   │  │ 事件广播  │
│/plan /do  │  │ 重试/停止   │  │ subscribe │
│Ctrl+C绑定 │  │ 选择工具集   │  │ fire      │
└───────────┘  └─────┬───────┘  └─────┬────┘
                     │               │
              ┌──────▼───────┐  ┌────▼──────────┐
              │DialogueManager│  │TuiEventListener│
              │ 对话历史管理   │  │ 终端渲染       │
              │ doRound()     │  │ 打印文本/工具   │
              │ LLM调用+工具   │  │ 打印token用量   │
              └──────┬───────┘  └────────────────┘
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
   ┌──────────┐ ┌────────┐ ┌──────────┐
   │LLMProvider│ │ToolReg │ │ToolExec  │
   │调API/SSE  │ │工具目录 │ │超时控制   │
   └──────────┘ └────────┘ └──────────┘
```

### 每个模块一句话

| 模块 | 一句话 |
|------|--------|
| **Main** | 启动入口，创建所有对象，把它们连起来 |
| **SessionManager** | 死循环读用户输入，识别 `/plan` `/do` `/exit`，其余交给 AgentLoop |
| **AgentLoop** | ReAct 循环控制器——选工具、调 doRound、判停止、发循环事件 |
| **DialogueManager** | 对话历史 + 单轮执行——调 LLM → 收回复 → 有工具就执行 → 写历史 |
| **EventBus** | 大喇叭。谁发事件谁就调 `fire()`，谁想听就 `subscribe()`，两边互不知道 |
| **TuiEventListener** | 订阅者。收到事件翻译成终端文字：TextDelta→逐字打印，ToolCallCompleted→工具进度，TokenUsage→用量统计 |
| **LLMProvider** | 封装 Anthropic/OpenAI API，发起 HTTP 流式请求，逐 token 回调 |
| **ToolRegistry** | 工具注册表，按名字查找工具 |
| **ToolExecutor** | 给每个工具调用加超时上限（默认 30 秒），超时就中断 |
| **Tool** | 工具接口：名字、描述、参数 schema、执行方法、isReadOnly |

---

## 三、一次完整调用：从输入到回复

以"帮我读一下 pom.xml"为例。

### 阶段 A：启动接线

```
Main.main()
  ├─ 读 config.yaml (API Key, 模型, 超时, maxIterations)
  ├─ new AnthropicProvider(...)        → provider
  ├─ new TerminalIO()                   → terminalIO
  ├─ new ToolRegistry + 注册 6 个工具   → toolRegistry
  ├─ new ToolExecutor(30s)              → toolExecutor
  ├─ new DialogueManager(systemPrompt, toolRegistry, toolExecutor, tools)  → dialogue
  ├─ new EventBus()                     → bus
  ├─ bus.subscribe(new TuiEventListener(terminalIO))   → 终端渲染订阅
  ├─ new AgentLoop(dialogue, provider, toolRegistry, bus, 20)  → agentLoop
  ├─ new SessionManager(terminalIO, agentLoop, provider::cancel)  → session
  └─ session.start()
```

### 阶段 B：用户输入

```
SessionManager.start()
  └─ while(true):
        terminalIO.readLine("> ")      ← 阻塞等输入
        用户输入: "帮我读一下 pom.xml"
        不是 /plan /do /exit
        → agentLoop.run("帮我读一下 pom.xml")
```

### 阶段 C：AgentLoop 启动

```
AgentLoop.run("帮我读一下 pom.xml")
  ├─ dialogue.addUserMessage("帮我读一下 pom.xml")
  │      → history = [SYSTEM(...), USER("帮我读一下 pom.xml")]
  │
  ├─ iteration = 1
  ├─ bus.fire(LoopIterationStarted(1))
  │      → TuiEventListener 收到，不关心，空实现
  │
  └─ runRoundWithRetry(...)
       └─ dialogue.doRound(provider, [6个工具], bus, totalInput, totalOutput)
```

### 阶段 D：doRound — LLM 调用

```
DialogueManager.doRound()
  ├─ buildApiMessages()
  │      → [SYSTEM(...), USER("帮我读一下 pom.xml")]
  │
  ├─ 建 StreamCallback:
  │     onToken(token)     → textBuilder + bus.fire(TextDelta)
  │     onReasoning(token) → bus.fire(ReasoningDelta)
  │     onToolCall(tc)     → pendingToolCalls.add(tc)
  │     onUsage(in, out)   → 累计 + bus.fire(TokenUsage)
  │     onComplete()       → latch.countDown()
  │     onError(e)         → 存异常 + latch.countDown()
  │
  ├─ provider.streamChat(messages, tools, collector)  ← 异步，立即返回
  └─ latch.await()                                     ← 阻塞等
```

### 阶段 E：Provider 线程 — 流式接收

```
AnthropicProvider 读 SSE 流 ←────────────────── Anthropic API
  │
  ├─ content_block_start {type:"text"}
  │
  ├─ content_block_delta {text:"好"}    → collector.onToken("好")
  │    ├─ textBuilder.append("好")              ← 积攒
  │    └─ bus.fire(TextDelta("好"))             ← 广播
  │         └─ TuiEventListener.onTextDelta
  │              └─ terminalIO.printToken("好")  ← 终端显示 "好"
  │
  ├─ ... 逐 token: 的, ，, 我, 来, 读取, ...
  │    终端: "好的，我来读取"
  │
  ├─ content_block_start {type:"tool_use", name:"read_file", id:"toolu_001"}
  ├─ content_block_delta {partial_json:'{"filePath":"pom.xml"}'}
  │    → collector.onToolCall(ToolCall("toolu_001","read_file",{filePath:"pom.xml"}))
  │         → pendingToolCalls.add(...)          ← 静默收集
  │
  ├─ message_stop
  │    ├─ collector.onUsage(input=1200, output=150)
  │    │    ├─ totalInputTokens  += 1200
  │    │    ├─ totalOutputTokens += 150
  │    │    └─ bus.fire(TokenUsage(1200, 150, 累计1200, 累计150))
  │    │         └─ TuiEventListener.onTokenUsage
  │    │              ├─ terminalIO.setTotalTokens(1350)
  │    │              └─ System.out.println("  ↑1.2k ↓150 · 累计 1.4k")
  │    │
  │    └─ collector.onComplete()
  │         └─ latch.countDown()                 ← 唤醒 doRound
```

### 阶段 F：doRound — 执行工具

```
latch.await() 返回了，继续执行 doRound 的剩余代码：

  ├─ streamError == null   ✓
  ├─ pendingToolCalls = [read_file("toolu_001", {filePath:"pom.xml"})]
  │   不为空 → 走工具分支
  │
  ├─ hasUnknown?
  │    toolRegistry.get("read_file") → ReadFileTool 实例 ✓
  │    → hasUnknown = false
  │
  ├─ history.add(ASSISTANT("好的，我来读取", [read_file]))
  │
  ├─ executeToolBatch([read_file])
  │    ├─ 分组: readOnly=[read_file], mutation=[]
  │    │
  │    ├─ 只读组并发:
  │    │    executeOne(read_file)
  │    │      ├─ bus.fire(ToolCallStarted("read_file","toolu_001"))
  │    │      ├─ toolExecutor.execute(read_file, "toolu_001", {filePath:"pom.xml"})
  │    │      │     → ReadFileTool.execute() 
  │    │      │     → 读文件，200ms 后返回 ToolResult("...", false)
  │    │      └─ bus.fire(ToolCallCompleted("read_file","toolu_001",false,200))
  │    │           └─ TuiEventListener.onToolCallCompleted
  │    │                └─ System.out.println("  read_file pom.xml ✓ 200ms")
  │    │
  │    └─ 副作用组: 空，跳过
  │
  ├─ history.add(USER("", [], [ToolResult("...")]))
  │
  └─ return RoundResult(false, [read_file], "好的，我来读取", false)
       //                        ↑ completed=false，表示还要继续
```

### 阶段 G：回到 AgentLoop

```
AgentLoop.run() 继续:
  ├─ bus.fire(LoopIterationEnded(1, 1200ms))
  ├─ result.completed() == false  → 不终止
  ├─ result.hasUnknown() == false → 不纠偏
  ├─ unknownToolStreak = 0
  │
  ├─ iteration = 2
  ├─ bus.fire(LoopIterationStarted(2))
  └─ runRoundWithRetry(...)
       └─ dialogue.doRound(...)  ← 第二轮
            │ history 现在是:
            │ [SYSTEM, USER("帮我读"),
            │  ASSISTANT("好的，我来读取", [read_file]),
            │  USER("", [], [pom.xml内容])]
            │
            └─ LLM 看到完整的工具往返历史
                 → 模型输出: "pom.xml 的内容如下：这是一个 Maven 项目..."
                 → 无 toolCall
                 → return RoundResult.COMPLETED
```

### 阶段 H：完成

```
AgentLoop.run() 继续:
  ├─ result.completed() == true
  ├─ bus.fire(AgentFinished(COMPLETED))
  │    └─ TuiEventListener.onAgentFinished
  │         COMPLETED → 不打印额外信息
  └─ return

SessionManager.start() 回到循环顶部:
  └─ terminalIO.readLine("> ")  ← 等待下一条输入
```

### 终端最终效果

```
> 帮我读一下 pom.xml

好的，我来读取
  ↑1.2k ↓150 · 累计 1.4k
  read_file pom.xml ✓ 200ms

pom.xml 的内容如下：这是一个 Maven 项目，依赖包括...

  ↑800 ↓120 · 累计 2.1k

>
```

---

## 四、工具调用细节

### 4.1 ReAct 循环是怎么转起来的

ReAct = Reasoning + Acting。核心思想：模型不只是回答问题，而是像人一样**思考 → 行动 → 观察结果 → 再思考**。
```
AgentLoop.run() 就是一个 while 循环：

while (iteration < 20) {
    ① 选工具集 (Plan Mode 只给只读工具, Full Mode 全给)
    ② dialogue.doRound(tools)  ← 一次完整的"思考+行动"
    ③ 看结果:
       无工具调用 → 做完了，退出
       有工具但是未知 → 计数器+1
          连续3次 → 模型疯了，退出
          不到3次 → 把错误写进历史，下轮纠正
       有工具且正常 → 工具已执行，历史已写入，下轮继续
}
```
#### 那是怎么去反馈结果并怎么观察结果和怎么再思考的呢？

不是 AgentLoop 自己思考，而是把工具结果**写回对话历史**，让 LLM 在下一轮看到。

```
第 1 轮:
  发 LLM 请求 → history = [SYSTEM, USER("帮我读 pom.xml")]
  模型回复: "好的" + tool_call(read_file)
  执行 read_file → 拿到 "pom.xml内容是..."
  
  写回历史:
    history = [
      SYSTEM,
      USER("帮我读 pom.xml"),
      ASSISTANT("好的", [read_file]),          ← 告诉 LLM: 上一轮你说了这些
      USER("", [], [ToolResult("pom.xml内容是...")])  ← 告诉 LLM: 工具返回了这个
    ]

第 2 轮:
  发 LLM 请求 → history 已经包含完整上下文
  模型看到上一轮的结果，决定: "内容看到了，任务完成，总结一下"  → 无 tool_call → 结束
```

核心机制：**对话历史就是 Agent 的"记忆"**。每一轮把工具结果追加到 history 末尾，下一轮 LLM 自然就能"观察"上一轮发生了什么。


### 4.2 工具为什么要分两批执行
```
假设模型一次返回了 5 个工具调用:
  [read_file(A), write_file(B), read_file(C), execute_command(D), search_content(E)]

不能全部并发跑——
  write_file(B) 可能依赖 execute_command(D) 先创建一个目录
  但 read_file(A) 和 search_content(E) 互相完全独立

所以:
  第一步: A, C, E 三个只读工具同时跑（快）
  第二步: 等它们全跑完，B 再跑，B 跑完 D 再跑
```
#### 那会有那种创建了文件，再去查看更改的吗？

不会在同一轮做。模型应该拆成两轮：

```
第 1 轮: write_file(config.yaml)           → 创建文件
         执行完，结果写入历史
第 2 轮: read_file(config.yaml)             → 验证内容
         LLM 看到上一轮的写入结果，确认正确 → 结束
```

同一轮返回的多个工具调用，语义是"这些操作互不依赖，可以任意顺序执行"。如果需要先写再读，那是两个独立的意图，应该分两轮——这样第二轮读的时候历史里已经有写的结果，一定不会读到空文件。强行让系统去猜"副作用先还是只读先"反而不可靠。

分组依据是 `Tool.isReadOnly()`：
#### 只能靠这个分组吗？

目前是。`isReadOnly()` 是唯一的分组依据，每个工具自己声明。

局限性：两个只写工具如果互相依赖（比如 `mkdir a/b` 和 `write_file a/b/c.txt`），`isReadOnly` 区分不了——它们都是 mutation 组，会串行执行，刚好保证了顺序。但如果两个 mutation 工具**没有**依赖关系，串行就是浪费。

更好的方案（未来可以做的）：让 LLM 在 tool_call 里自己声明依赖关系——"B 依赖 A 的结果"——但 OpenAI/Anthropic 目前都不支持这个。所以 isReadOnly 两分组是当前最实用的折中。
```java
// ReadFileTool / FindFilesTool / SearchContentTool
public boolean isReadOnly() { return true; }

// WriteFileTool / EditFileTool / ExecuteCommandTool
// 不覆盖，继承默认的 return false
```

### 4.3 结果顺序为什么不会乱

原始请求顺序是 `[A, B, C, D, E]`。A/C/E 并发跑，可能 C 先跑完、E 其次、A 最后。但 `executeOne` 内部按原始位置填充：

```java
int idx = allCalls.indexOf(tc);  // A 的下标永远是 0
results.set(idx, result);        // 不管 A 第几个跑完，结果都放在 results[0]
```

API 要求 `tool_result` 的顺序必须和 `tool_use` 声明顺序一样——否则 Anthropic 返回 400。

### 4.4 工具执行的超时保护

每个工具调用都经过 `ToolExecutor.execute()`，它内部：

```java
Future<ToolResult> future = executor.submit(() -> tool.execute(params));
//等这个任务完成，但最多等 30 秒。超过 30 秒还没做完就抛异常，然后取消任务。
ToolResult result = future.get(30, TimeUnit.SECONDS);  // 30 秒超时
// 超时 → cancel 任务 + 返回 isError=true
```

如果工具自己写了死循环或 sleep(999)，不会把整个 Agent 卡死。

### 4.5 未知工具的容错

模型有时会幻觉出不存在工具名。DialogueManager 发现 `toolRegistry.get(name) == null` 时，会：

1. 给这个工具构造一个 error ToolResult：`"未找到工具: xxx"`
2. 正常写入 history
3. 标记 `hasUnknown=true`

AgentLoop 拿到 `hasUnknown=true` 后，计数器+1，**不立即终止**——错误结果已经喂给模型了，下轮它看到这个反馈通常会自动纠正。只有连续 3 轮都出现未知工具才终止。

### 4.6 工具执行在哪个线程上

```
Provider IO 线程:
  parseSSEStream → onComplete → latch.countDown()
  latch.countDown() 后 doRound 继续在此线程执行工具，Provider 线程被占用直到工具跑完

AgentLoop 线程 (session 主线程):
  agentLoop.run() → doRound() → latch.await() 阻塞
  工具执行时它只是等着
```

这意味着**工具执行期间不能发新的 HTTP 请求**——Provider 线程正忙着跑工具。但这是合理的：工具跑完拿到结果才能确定下一轮请求体。

### 4.7 五种停止原因

Agent 不会永远跑下去。有五种情况会让它停下来：

#### ① COMPLETED — 正常完成

```java
// DialogueManager.doRound()
if (pendingToolCalls.isEmpty()) {
    history.add(new Message(Role.ASSISTANT, textBuilder.toString()));
    return RoundResult.COMPLETED;    // ← 模型没调工具，说完了
}

// AgentLoop.run()
if (result.completed()) {
    bus.fire(AgentFinished(COMPLETED));
    return;
}
```

模型觉得任务完成了，没调任何工具，直接回复了文本。**这是最常见、最正常的结束方式。** 终端不额外提示，直接回到输入状态。

#### ② MAX_ITERATIONS — 触达上限

```java
// AgentLoop.run()
while (iteration < maxIterations) {   // 默认 20
    ...
}
// 循环跑完了 20 轮还没结束
bus.fire(AgentFinished(MAX_ITERATIONS));
```

模型一直调工具，始终不说"做完了"。可能原因：任务实在太复杂，或者模型在无意义的循环中兜圈。**安全网，防止无限烧 token。** 终端显示 `⚠ 达到最大迭代次数，已停止`。

#### ③ CANCELLED — 用户中断

```java
// AgentLoop.run() — 每轮顶部
if (cancelled) {
    bus.fire(AgentFinished(CANCELLED));
    return;
}

// runRoundWithRetry() — 重试循环中
if (cancelled || attempt >= MAX_STREAM_RETRIES) {
    return null;
}
```

用户按了 Ctrl+C。两处检查点确保即使正在重试等待也能立即退出。终端显示 `^C 终止`。

#### ④ UNKNOWN_TOOL_LOOP — 连续未知工具

```java
// AgentLoop.run()
if (result.hasUnknown()) {
    unknownToolStreak++;         // 计数器 +1
    if (unknownToolStreak >= 3) {
        bus.fire(AgentFinished(UNKNOWN_TOOL_LOOP));
        return;                  // 连续 3 轮 → 模型幻觉严重，终止
    }
    continue;                    // 不到 3 次 → 给机会纠正
}
unknownToolStreak = 0;           // 工具都合法 → 清零
```

模型连续 3 轮调用不存在的工具（如幻觉出来的 `delete_everything`）。前两次会给错误反馈让它自我纠正，第三次直接停。终端显示 `⚠ 连续调用未知工具，已停止`。

#### ⑤ STREAM_ERROR — 网络故障

```java
// AgentLoop.runRoundWithRetry()
for (int attempt = 0; attempt <= 3; attempt++) {
    try {
        return dialogue.doRound(...);
    } catch (Exception e) {
        if (cancelled || attempt >= 3) {
            return null;    // 3 次全失败，放弃
        }
        Thread.sleep((1L << attempt) * 1000L);  // 等 1s / 2s / 4s 后重试
    }
}

// AgentLoop.run()
if (outcome == null) {
    bus.fire(AgentFinished(STREAM_ERROR));
    return;
}
```

LLM 请求连续 3 次失败（网络断了、API 挂了），指数退避重试耗尽后放弃。终端显示 `⚠ 网络请求失败，已重试3次仍无法恢复`。

#### 五种停止原因一览

| 停止原因 | 触发条件 | 终端显示 | 正常? |
|---------|---------|---------|:---:|
| COMPLETED | 模型自然说完，不调工具 | — | 正常 |
| MAX_ITERATIONS | 循环超过 20 轮 | `⚠ 达到最大迭代次数，已停止` | 异常 |
| CANCELLED | 用户按 Ctrl+C | `^C 终止` | 主动 |
| UNKNOWN_TOOL_LOOP | 连续 3 轮调不存在的工具 | `⚠ 连续调用未知工具，已停止` | 异常 |
| STREAM_ERROR | LLM 请求重试 3 次全失败 | `⚠ 网络请求失败，已重试3次仍无法恢复` | 异常 |

---

## 五、用户中断（Ctrl+C）

### 触发链

```
用户按 Ctrl+C
  → 操作系统发 SIGINT 信号
  → TerminalIO 捕获
  → 执行 interruptHandler:
       ├─ agentLoop.cancel()        // 设 cancelled = true（软标记）
       └─ provider.cancel()         // response.close()（物理断连）
```

### 为什么需要两步

| | 作用 | 缺了会怎样 |
|---|---|---|
| `agentLoop.cancel()` | 设 `cancelled = true` | Provider 分不清"主动取消"和"网络故障"，会走重试（等 7 秒） |
| `provider.cancel()` | 关闭 TCP 连接 | `latch.await()` 永远不会醒，AgentLoop 卡死 |

### 时序

```
Ctrl+C 时恰好正在等 LLM 回复:
  provider.cancel() → response.close()
    → OkHttp 读流线程抛 IOException
    → provider 检查 cancelled=true → onError("已中断")
    → StreamCallback.onError → latch.countDown()
    → doRound() 抛 RuntimeException
    → runRoundWithRetry 检查 cancelled → return null
    → AgentLoop.run() 收 null → fire(STREAM_ERROR) → return
  ≈ 毫秒级响应

Ctrl+C 时恰好 LLM 刚回复完、正在执行工具:
  executeToolBatch 进行中，没有 HTTP 连接可断
  工具执行完 → while 顶部 if(cancelled) return
  ≈ 最长等一个工具执行完成
```

---

## 六、流式数据怎么到终端

流式数据走两条路，同时进行：

```
StreamCallback.onToken("好")
  ├─→ textBuilder.append("好")         路 A: 积攒，用于构建 assistant 消息
  └─→ bus.fire(TextDelta("好"))       路 B: 广播
        └─→ TuiEventListener.onTextDelta
              └─→ terminalIO.printToken("好")   终端显示
```

- **路 A**：在 doRound 内部，积攒的完整文本在 onComplete 时写进 history
- **路 B**：通过 EventBus 交给 TuiEventListener，实时渲染到终端

两条路在同一个回调里触发，不互相影响。如果将来加了日志订阅者，只需要 `bus.subscribe(new LogListener())`，不改这行代码。

### 为什么不能合并

如果让 `terminalIO.printToken` 直接负责积攒，TuiEventListener 就必须暴露"取文本"的方法给 DialogueManager——两个模块就耦合了。保持分离，TuiEventListener 只管渲染，DialogueManager 只管业务。


### 怎么知道是 plan 模式的？

根据代码分析，当前 bingtangCode 中 LLM 并不知道自己在 plan 模式。机制是纯工具过滤：

1. AgentLoop.selectTools() 在 plan 模式下只返回 isReadOnly() == true 的工具（read_file、find_files、search_content）
2. 这些工具直接传给 API 的 tools 参数，写工具根本不出现在请求中
3. 系统提示词是静态的，不包含任何"当前处于 plan 模式"的信息

对比 Claude Code 的做法：它会向消息列表注入一条系统消息，明确告知模型当前处于 plan 模式、只能做研究和规划、不能写代码。