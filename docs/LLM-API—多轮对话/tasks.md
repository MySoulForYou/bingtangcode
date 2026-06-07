# bingtangCode — Tasks v0.1



---
## 0.整体流程
**Main** 
- 读取 config.yaml 配置文件（看看是用 OpenAI 还是 Anthropic，API Key 是什么)） 
- 初始化大模型连接接口（Provider）。
- 创建对话管理器（DialogueManager）和带有输入边框的终端界面（TerminalIO）。
- 把它们全部塞给 SessionManager 会话管理器

**sessionmanger**
- 显示欢迎界面
- 交互循环--抓取输入--存入历史会话（user)），从对话管理器返回 assistent 的流式回复打印到控制台，主线程使用 CountDownLatch 进入挂起（锁定输入，防止您在 AI 说话时乱打字）。
- 调用对话管理器，负责把历史消息发送给 provider，并把 provider 的返回值存入历史消息，并把返回值传递至会话管理器
- provider 接口对外隐藏内部对系统消息，用户信息的封装及 http 发送，并解析好LLM 的回复，以流式返回
## 1. 项目骨架搭建

**影响文件**: `pom.xml`, `src/main/java/com/bingtangcode/Main.java`, 四个包的目录结构

**依赖**: 无（这是第一件事）

**目标**: 创建一个能编译、能跑的 Maven 空壳，后续所有代码都在这个壳里长。

**做什么**:

先用 Maven 标准目录布局把项目立起来：
```
QAQcode/
  pom.xml
  src/main/java/com/bingtangcode/
    Main.java             ← 程序入口
    core/                 ← 会话管理器、对话管理器（任务 6、8）
    llm/                  ← LLM 抽象接口与 provider 实现（任务 2、3、4）
    tui/                  ← 终端输入输出（任务 7）
    config/               ← 配置文件读取（任务 5）
```

`pom.xml` 里需要三样东西：
- `<release>21</release>` — 锁定 Java 21
- JLine3 依赖 — 终端行编辑和历史翻找（任务 7 会用到）
- OkHttp 依赖 — HTTP 客户端，发 API 请求用（任务 3、4 会用到）
- Jackson 依赖 — JSON 序列化/反序列化（LLM API 请求体和响应都是 JSON）

`Main.java` 现阶段只需要打印一行 "bingtangCode" 和一行欢迎语然后退出，验证 Maven 能正常编译和打包。

**完成标志**: `mvn compile` 无报错，`mvn package` 产出 jar。

---

## 2. LLM 抽象层接口与数据模型

**影响文件**:
- `src/main/java/com/bingtangcode/llm/LLMProvider.java`
- `src/main/java/com/bingtangcode/llm/Message.java`
- `src/main/java/com/bingtangcode/llm/StreamCallback.java`
- `src/main/java/com/bingtangcode/llm/Role.java`

**依赖**: 任务 1（项目骨架存在）

**目标**: 定义四个核心类型，它们是整个 LLM 模块的"语言"——对话管理器和 TUI 只跟这些接口打交道，不关心背后是 Anthropic 还是 OpenAI。

**做什么**:

逐个说明四个文件的职责：

**`Role.java`** — 一个枚举，只有三个值：`SYSTEM`、`USER`、`ASSISTANT`。每条对话消息都得标记是谁说的，从"你"（USER）到"AI"（ASSISTANT），还有一条系统提示（SYSTEM）告诉 AI 它是什么角色。

**`Message.java`** — 一条消息，就是 role + content 两个字段的 record。比如 `new Message(Role.USER, "帮我写一个排序函数")`。这是对话历史的最小单位。

**`StreamCallback.java`** — 一个回调接口，三个方法。它是 LLM Provider 和外界（TUI、对话管理器）之间的通知协议。


三个方法的输入和职责：
- onToken：输入是一个文本片段，Provider 每收到一个 token 就调一次。职责是让 TUI 把文本追加显示到终端。
- onComplete：无输入。Provider 在回复完整结束时调用。职责是通知对话管理器此轮回复已结束，将完整文本存入历史。
- onError：输入是一个异常对象。Provider 在请求失败时调用。职责是让 TUI 显示红色错误信息，本轮不更新历史。

**`LLMProvider.java`** — 最核心的接口，所有 provider 必须实现的两个方法：
核心功能：接收一段对话历史，指定一个回调，把 AI 的生成结果通过回调推出去。调用方不关心历史怎么打包成 HTTP 请求、SSE 怎么解析——这些都是实现类的职责。



- `void streamChat(List<Message> history, StreamCallback callback)` — 接收完整对话历史，异步调用远程 API，token 逐一到就调 callback.onToken，结束调 onComplete，出错调 onError。
- `String getName()` — 返回 provider 名字，如 "Anthropic" 或 "OpenAI"，给用户看当前用的是哪个。

**为什么要这样设计**：调用方（对话管理器）只依赖接口，不依赖具体实现。以后加新 provider（比如 Ollama 本地模型）只需要新增一个实现类，其他地方一行不用改。

**完成标志**: 四个 `.java` 文件编译通过，接口语义清晰。

---

## 3. Anthropic Provider 实现

**影响文件**: `src/main/java/com/bingtangcode/llm/AnthropicProvider.java`

**依赖**: 任务 2（LLMProvider 接口和 StreamCallback 已定义）

**参考**: [Anthropic Messages API 文档](https://docs.anthropic.com/en/api/messages) — 关注 streaming 端点

**目标**: 让程序能跟 Claude 说话。这是第一个具体 provider，把抽象接口和真实的远端服务连接起来。

**做什么**:

构造时接收四个配置值：API Key、model 名称（如 `claude-opus-4-7`）、API 端点 URL（默认 `https://api.anthropic.com/v1/messages`）、max_tokens（默认 4096）。

实现 `streamChat` 方法，核心流程分四步：

**第一步 — 组装请求体**。遍历 `List<Message>` 历史，转成 Anthropic API 要求的 JSON 格式。注意 Anthropic 的 messages 数组里不能出现 system 类型的消息——system prompt 要单独放到 `system` 字段，只有 user 和 assistant 消息放在 `messages` 数组里。请求体里还要设 `stream: true` 和 `max_tokens`（暂定 4096）。

**第二步 — 发起 HTTP POST**。用 OkHttp 发请求，设置三个关键 header：
- `x-api-key: <你的API Key>`
- `anthropic-version: 2023-06-01`
- `Content-Type: application/json`

**第三步 — 解析 SSE 流响应**。streaming 开启后，Anthropic 返回的不是一个完整 JSON，而是逐行发送的 Server-Sent Events，格式是：
```
event: content_block_delta
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"你好"}}

event: message_stop
data: {"type":"message_stop"}
```
需要逐行读取响应体，找到 `data:` 开头的行，解析 JSON，如果是 `content_block_delta` 类型且有 `text` 字段，就调 `callback.onToken(text)`。如果是 `message_stop`，说明回复结束，调 `callback.onComplete()`。

**第四步 — 错误处理**。网络错误（无连接）直接回调 onError。HTTP 错误码需要区分：401 表示 API Key 错，429 表示频率限制，5xx 表示服务端故障——这些都应该给 onError 传一个带清晰中文描述信息的异常。

**注意**: OkHttp 的 streaming 模式下，`response.body().byteStream()` 可以逐块读取。建议用一个线程来读取，因为 streaming 是一个长时间操作，不能阻塞调用方。

**完成标志**: 配置好 API Key 后，调用 `streamChat` 能收到 Claude 的流式回复，token 逐个到达。

---

## 4. OpenAI Provider 实现

**影响文件**: `src/main/java/com/bingtangcode/llm/OpenAIProvider.java`

**依赖**: 任务 2（LLMProvider 接口和 StreamCallback 已定义）

**参考**: [OpenAI Chat Completions API 文档](https://platform.openai.com/docs/api-reference/chat) — 关注 streaming 参数

**目标**: 让程序也能跟 GPT 说话。证明抽象接口真的有效——换上 OpenAI provider，对话管理器和 TUI 不需要任何改动。

**做什么**:

和 Anthropic provider 结构相同，但 API 格式有四个关键差异：

**差异一 — 消息格式**。OpenAI 的消息数组不区分 system 和 user/assistant——直接传 `{"role": "system", "content": "..."}` 作为 messages 数组的第一条就行，不需要像 Anthropic 那样把 system 单独提出来。

**差异二 — 请求参数**。OpenAI 用 `stream: true`（不是 Anthropic 的 `"stream": true`），参数位置相同。max_tokens 参数名一样。

**差异三 — Header 认证**。OpenAI 用 `Authorization: Bearer <API Key>` 而不是 `x-api-key`。

**差异四 — SSE 响应格式**。OpenAI 的 streaming 响应也走 SSE，但数据结构不同：
```
data: {"id":"chatcmpl-xxx","choices":[{"delta":{"content":"你好"}}]}
```
token 文本在 `choices[0].delta.content` 里。注意首次 delta 可能没有 content（只有 role），要做 null 安全处理。结束信号是 `choices[0].finish_reason` 不为 null，或者读到 `data: [DONE]`。

**完成标志**: 配置好 OpenAI Key 后调用 `streamChat`，GPT 流式回复正常。

---

## 5. 配置管理

**影响文件**: `src/main/java/com/bingtangcode/config/ConfigManager.java`, `config.example.yaml`, `.gitignore`

**依赖**: 任务 1（项目骨架和目录结构存在）

**目标**: 程序的所有可变设置都集中在一个地方管理，API Key 不写死在代码里。

**做什么**:

**配置文件位置和格式**: 项目根目录下的 `config.yaml`，YAML 格式。项目提供 `config.example.yaml` 模板可安全提交 git，`config.yaml` 被 `.gitignore` 排除。一个完整的配置文件长这样：

```yaml
provider: anthropic
max_tokens: 4096

anthropic:
  api_key: sk-ant-xxxxx
  model: claude-opus-4-7
  endpoint: https://api.anthropic.com/v1/messages

openai:
  api_key: sk-xxxxx
  model: gpt-4o
  endpoint: https://api.openai.com/v1/chat/completions
```

- `provider` — 选择使用的 provider，支持 `anthropic` / `openai`
- `max_tokens` — 可选，Anthropic 必填（默认 32768），OpenAI 请求体中不传此字段，由模型自行决定输出长度
- `{provider}.api_key` — 必填，API 密钥
- `{provider}.model` — 可选，模型名（有默认值）
- `{provider}.endpoint` — 可选，API 端点，不填用官方默认地址。支持 Ollama 等三方兼容接口（如 `http://localhost:11434/v1/chat/completions`）

**ConfigManager 职责**：
- 构造时用 Jackson YAML 解析 `config.yaml`，转为 `Map<String, Object>` 后逐字段提取
- 提供 getter：`getProvider()`、`getMaxTokens()`、`getAnthropicApiKey()`、`getAnthropicModel()`、`getAnthropicEndpoint()`、`getOpenAiApiKey()`、`getOpenAiModel()`、`getOpenAiEndpoint()`
- 文件不存在 → 打印引导信息（含 `cp config.example.yaml config.yaml` 提示），exit code 0
- 当前 provider 的 api_key 为空 → 打印明确提示，exit code 1
- 不负责选择 provider，Main 来选

**重要语义**：ConfigManager 只负责把配置读出来并校验必填项。谁决定用哪个 provider、如何组装 Provider 实例是 Main 的职责（任务 9）。

**完成标志**: 创建不同内容的 config.yaml 后，ConfigManager 各 getter 返回对应值；自定义 endpoint 和 max_tokens 正确读取；删除 config.yaml 后程序打印引导信息。

---

## 6. 对话管理器

**影响文件**: `src/main/java/com/bingtangcode/core/DialogueManager.java`

**依赖**: 任务 2（LLMProvider、Message、StreamCallback 已定义）、任务 5（配置管理可用）

**目标**: 对话管理器是用户与 LLM 之间的"翻译官"。它记住所有说过的话，每轮把完整历史发给 LLM，再把 AI 的回复记下来。

**做什么**:

内部维护一个 `List<Message>`，这个列表就是对话的"记忆"。

**初始化**: 构造时接收一段 system prompt 文本（由 Main 传进来），生成一条 `Message(Role.SYSTEM, systemPrompt)` 作为列表的第一条。这条消息告诉 AI 它是什么角色——比如 "你是一个终端 AI 编程助手，名叫 bingtangCode，帮助用户解决编程问题。"

**方法一 — `addUserMessage(String content)`**: 用户每输入一句话就调用。创建 `Message(Role.USER, content)`，追加到历史末尾。

**方法二 — `streamResponse(LLMProvider provider, StreamCallback callback)`**: 核心方法。先调用 `buildApiMessages()` 清洗历史，将清洗后的消息列表传给 provider，让 LLM 开始流式生成回复。内部做一个包装回调：在 onToken 时收集 token 到 StringBuilder，同时把 token 传给外层的 callback 让 TUI 打印；在 onComplete 时将收集到的完整文本以 `Message(Role.ASSISTANT, 完整文本)` 形式存入历史。

**方法三 — `buildApiMessages()`** (包级可见): 格式转换层。遍历 history，合并连续相同角色的消息（用 `\n` 拼接），校验最终列表的最后一条必须是 USER（Anthropic API 硬性要求）。返回清洗后的副本，原始 history 不受影响。

**调用示例** — 假设当前历史是 [SYSTEM("你是bingtangCode"), USER("你好")]：

```
输入: dialogueManager.addUserMessage("你好");
      dialogueManager.streamResponse(provider, tuiCallback);

内部过程:
  1. streamResponse 收到调用，当前历史 = [SYSTEM(...), USER("你好")]
  2. buildApiMessages() 清洗历史:
     - 合并连续同角色 → 无相邻同角色，不变
     - 校验末条是 USER → 通过
     - 返回 [SYSTEM(...), USER("你好")]
  3. 创建包装 callback:
     - onToken(t) → sb.append(t); 外层callback.onToken(t);
     - onComplete() → addMessage(Role.ASSISTANT, sb.toString()); 外层callback.onComplete();
  4. 调用 provider.streamChat(清洗后列表, 包装callback)
  5. LLM 流式返回... 包装 callback 同步收集和转发
  6. onComplete 后，历史 = [SYSTEM(...), USER("你好"), ASSISTANT("你好！有什么可以帮你的？")]

输出: 历史新增了一条 ASSISTANT 消息，TUI 上用户看到了逐字打字效果。
```

**方法三 — `getHistory()`**: 返回当前历史列表的只读视图，供后续查阅或调试。

**完成标志**: 连续三轮对话后，`getHistory()` 返回的列表包含 1 条 SYSTEM + 3 条 USER + 3 条 ASSISTANT = 7 条消息。

---

## 7. 终端 IO 层

**影响文件**: `src/main/java/com/bingtangcode/tui/TerminalIO.java`

**依赖**: 任务 1（项目骨架和 JLine3 依赖已就位）

**参考**: JLine3 官方文档的 `LineReader` 使用方式

**目标**: 把所有终端交互封装在一个类里。程序其他地方不需要知道 JLine3 的 API，也不需要徒手拼接 ANSI 转义码——所有终端操作都通过 TerminalIO 的方法完成。

**做什么**:

**初始化**: 创建 JLine3 的 `Terminal` 和 `LineReader` 实例。`LineReader` 是核心对象——它接管了标准输入，提供了行编辑、历史翻找、光标移动这些功能。配置历史文件路径为 `~/.bingtangcode/history`，这样关闭程序后下次启动还能翻到之前输入的命令。

**对外提供的方法**（都是同步的，调用方依次调用即可）:

**终端渲染示例** — 以一次完整对话为例，展示每个方法在终端上产生的视觉效果：

```
启动程序:
  terminalIO.printSystem("bingtangCode - 终端 AI 编程助手");     → [灰色] bingtangCode - 终端 AI 编程助手
  terminalIO.printSystem("当前模型: Anthropic Claude");      → [灰色] 当前模型: Anthropic Claude
  terminalIO.printSystem("输入 /exit 退出");                → [灰色] 输入 /exit 退出

用户输入:
  String input = terminalIO.readLine("> ");                 → > 什么是 Java record?  ← JLine3 编辑，上下翻历史
  // input = "什么是 Java record?"

显示用户输入:
  terminalIO.printUser(input);                               → [绿色] → 什么是 Java record?

AI 开始回复:
  terminalIO.printAssistantPrefix();                         → [蓝色] 🤖   （注意：不换行）
  terminalIO.printToken("Java");                             追加在蓝色前缀后面
  terminalIO.printToken(" ");
  terminalIO.printToken("record");
  terminalIO.printToken("是");
  // ...更多 token...
  terminalIO.newline();                                     → 换行，准备下一轮

出错时:
  terminalIO.printError("请求超时，请检查网络连接");          → [红色] 错误: 请求超时，请检查网络连接

终端上用户实际看到的完整画面:

  bingtangCode - 终端 AI 编程助手                    ← ANSI 灰色
  当前模型: Anthropic Claude                    ← ANSI 灰色
  输入 /exit 退出                              ← ANSI 灰色
  > 什么是 Java record?                        ← 用户输入（JLine 提供的提示符）
  → 什么是 Java record?                        ← ANSI 绿色（可选回显）
  🤖 Java record 是 Java 14 引入的一种特殊类...  ← ANSI 蓝色前缀 + 逐字打出的 token 流
  > █                                           ← 等待下一轮输入
```

**各方法定义**:

- `String readLine(String prompt)` — 显示提示符（如 "> "），等待用户输入一行文本。JLine3 自动提供：左右箭头移动光标、Backspace 删除、上箭头翻历史、Ctrl+A 到行首、Ctrl+E 到行尾。返回用户输入的字符串（不含换行）。用户按 Ctrl+D 返回 null 表示 EOF。

- `void printSystem(String text)` — 打印系统消息。用 ANSI 灰色（`\033[90m`），用于欢迎语和状态提示。末尾带换行。

- `void printUser(String text)` — 回显用户说的话。用 ANSI 绿色（`\033[32m`），前面加 `→ ` 前缀。这是可选的——如果觉得用户刚打完字又回显太冗余，可以不调用。

- `void printAssistantPrefix()` — 输出 AI 回复的起始标记。用 ANSI 蓝色（`\033[34m`），打印蓝色 `🤖 ` 前缀。不换行——因为紧接着就是 token 流。

- `void printToken(String token)` — 打印一个 token 片段。直接用 `System.out.print` 不加任何修饰（颜色已在 prefix 里设过）。如果 token 是 null 或空字符串则跳过。

- `void printError(String text)` — 打印错误信息。用 ANSI 红色（`\033[31m`），格式 `错误: <text>`，末尾带换行。

- `void newline()` — 输出一个换行。用于流式输出结束后。

- `void shutdown()` — 释放 JLine3 资源和终端设置。程序退出前调用，确保终端恢复。

**ANSI 颜色常量**: 建议在类里定义几个常量 `RESET`、`GRAY`、`GREEN`、`BLUE`、`RED`，每次输出颜色后用 `RESET` 还原，避免颜色泄漏。这些转义码只在 `TerminalIO` 里出现，不要散落到其他类。

**完成标志**: 在 main 里创建 TerminalIO，依次调用各方法，终端显示不同颜色的文本，上下箭头能翻历史。

---

## 8. 会话管理器（REPL 主循环）

**影响文件**: `src/main/java/com/bingtangcode/core/SessionManager.java`

**依赖**: 任务 6（DialogueManager）、任务 7（TerminalIO）

**目标**: 把对话管理器和终端 IO 串起来，形成一个永不退出的循环——除非用户说退出。这是程序的"心脏"。

**做什么**:

SessionManager 构造时接收 TerminalIO、DialogueManager 和 LLMProvider 三个对象。然后调用 `start()` 方法进入主循环。

**启动流程**: 先调用 TerminalIO 的方法展示欢迎界面——打印系统消息 "bingtangCode - 终端 AI 编程助手"，再打印当前使用的 provider 名称（来自 `LLMProvider.getName()`），还可以提示一句 "输入 /exit 退出"。

**主循环逻辑**（while true，跑在 main 线程上）:

**单轮对话的控制流** — 以用户说 "你好" 为例：

```
1. TerminalIO 显示 "> " 等待输入
2. 用户输入 "你好" 并按回车
3. SessionManager 判断: 不是 /exit，不是空行 → 继续
4. DialogueManager.addUserMessage("你好")
   → 内部历史 = [SYSTEM(...), USER("你好")]
5. TerminalIO.printAssistantPrefix()
   → 终端显示蓝色 "🤖 "
6. DialogueManager.streamResponse(provider, tuiCallback)
   → 内部: provider.streamChat(历史, 包装callback)
   → Anthropic API 返回 SSE 流...
   → 包装callback.onToken("你") → tuiCallback.onToken("你") → TerminalIO.printToken("你") → 终端显示"你"
   → 包装callback.onToken("好") → tuiCallback.onToken("好") → TerminalIO.printToken("好") → 终端显示"好"
   → ...更多 token...
   → 包装callback.onComplete()
     → 历史追加 ASSISTANT("你好！我是bingtangCode...")
     → tuiCallback.onComplete() → TerminalIO.newline()
7. 回到第 1 步，显示 "> " 等下一轮

终端最终画面:
  > 你好
  → 你好                        ← 绿色回显
  🤖 你好！我是 bingtangCode...       ← 蓝色前缀 + 逐字打出的完整回复
  > █                           ← 等待下一个问题
```

**Ctrl+C 中断**: 这是一个需要特别注意的场景。用户在等待 AI 流式回复时可能想中断它（比如回复太长或者跑偏了），但不能让整个程序退出。

实现思路：
- 在 SessionManager 里维护一个 `volatile boolean interrupted` 标志
- 在发起 streamChat 之前把标志设为 false
- 用 JLine 的 `UserInterruptException` 捕获 Ctrl+C——当用户按下 Ctrl+C，JLine3 会在 `readLine()` 或其他阻塞操作上抛这个异常
- 但实际上 streaming 期间线程不在 readLine 上阻塞——它在 provider 的读取线程里。所以更好的做法是使用 `Thread.interrupt()` 和 SSE 读取循环里的 `Thread.interrupted()` 检查
- 被中断后，打印一个 `^C` 提示（灰色），然后清理状态，回到循环顶部继续等下一轮输入

简化版处理：在 `streamChat` 返回后检查中断标志，如果被中断了就提前结束这轮，不把未完成的回复加入历史（或者加入截断版的回复）。

**退出流程**: 跳出循环后，调用 `TerminalIO.shutdown()`，打印告别信息。

**完成标志**: 启动程序后能连续多轮对话，/exit 正常退出，Ctrl+C 中断 AI 回复后能继续对话。

---

## 9. 接入主流程

**影响文件**: `src/main/java/com/bingtangcode/Main.java`

**依赖**: 任务 8（SessionManager 可用）

**目标**: Main 是程序的"装配车间"——在这里把之前造好的所有零件按正确的顺序拼起来，然后点火。

**做什么**:

Main.java 的 `main(String[] args)` 方法按以下顺序执行：

**第一步 — 加载配置**。`new ConfigManager()`，它会自动读取 `~/.bingtangcode/config`。如果文件不存在或 key 缺失，ConfigManager 自己会打印引导信息并 exit，所以 main 不需要额外判断。

**第二步 — 创建 LLMProvider**。根据 `config.getProvider()` 的返回值选择：
- 如果是 `"anthropic"` → `new AnthropicProvider(config.getAnthropicApiKey(), config.getAnthropicModel())`
- 如果是 `"openai"` → `new OpenAIProvider(config.getOpenAiApiKey(), config.getOpenAiModel())`
- 如果是其他值 → 打印 "不支持的 provider: xxx，支持: anthropic, openai" 后退出

**第三步 — 创建 DialogueManager**。传一个 system prompt 进去。第一版的 system prompt 简洁一点就行，比如 "你是 bingtangCode，一个终端 AI 编程助手。请帮助用户解决编程问题，回答使用中文。"

**第四步 — 创建 TerminalIO**。它自己会初始化 JLine3。

**第五步 — 创建 SessionManager**，传入上述三个对象。调用 `sessionManager.start()`，程序进入 REPL 循环直到用户退出。

**第六步 — 异常兜底**。整个 main 方法包在一个 try-catch 里面。未捕获的异常打印红色错误信息，然后 graceful 退出（确保终端状态恢复）。特别注意 `InterruptedIOException` 或 `UserInterruptException` 不要打印一大堆堆栈——把错误信息提取出来简洁显示即可。

**完成标志**: `mvn package` 后执行 `java -jar target/bingtangcode-*.jar`，看到欢迎信息，输入文本后 AI 回复，输入 /exit 退出。

---

## 10. 端到端验证

**影响文件**: 无（手动验证，不改代码）

**依赖**: 任务 9（完整程序可运行）

**目标**: 确认程序从启动到退出整个链路跑通，两个 provider 都能正常工作，所有关键路径都踩过一遍。

**验证步骤**:

**场景一 — Anthropic 正常对话**
1. 配置 `~/.bingtangcode/config` 使用 provider=anthropic，填好有效的 API Key
2. 启动程序：`java -jar target/bingtangcode-*.jar`
3. 看到欢迎信息中包含 "bingtangCode" 和 "Anthropic"
4. 输入 "你好，你是谁？" → 确认回复逐字流式出现在终端，有明显打字效果
5. 继续输入 "请用 Java 写一个 Hello World" → 确认回复包含有效的 Java 代码片段
6. 输入 /exit → 程序正常退出，终端恢复

**场景二 — 多轮对话上下文**
1. 启动后输入 "我叫张三"
2. 再输入 "我叫什么名字？" → AI 应该能回答 "张三"
3. 确认对话历史正确传递

**场景三 — Ctrl+C 中断**
1. 输入一个会让 AI 回复很长的问题，比如 "请详细解释 Java 的垃圾回收机制"
2. 在 AI 回复过程中按 Ctrl+C
3. 确认输出停止，出现 ^C 提示，且程序未退出
4. 再输入 "好的谢谢" → 确认可以继续正常对话

**场景四 — OpenAI Provider 切换**
1. 修改配置 `provider=openai`，填好有效的 OpenAI API Key
2. 重启程序，看到欢迎信息中显示 "OpenAI"
3. 输入 "你好" → 确认 GPT 正常回复
4. 验证抽象层正确——代码没有改动，只换了配置

**场景五 — 错误处理**
1. 故意把 API Key 改成一个无效值
2. 启动后发消息 → 应该看到红色错误提示（401 未授权），程序不崩溃
4. 删除配置文件 → 启动后看到引导信息，告诉用户如何创建配置

**完成标志**: 以上五个场景全部通过，程序在每个场景下的行为符合预期。


