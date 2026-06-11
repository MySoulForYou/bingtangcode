# bingtangCode

终端 AI 编程助手 — 在命令行里与 AI 对话，让它自主调用工具完成编程任务。

## 项目目标

构建一个类 Claude Code 的终端 AI 编程助手。

- 支持多 LLM provider（Anthropic / OpenAI），通过配置文件切换
- ReAct Agent Loop：模型自主思考→调工具→观察结果→再思考，多轮循环直到任务完成
- 流式输出，AI 回复逐字呈现，工具调用实时可见
- 六个核心工具：读文件、写文件、编辑文件、执行命令、搜索文件、搜索内容
- 事件驱动架构，模块完全解耦
- Java 21，终端原生体验

## 当前进度

**v0.3 — Agent Loop** 已完成：

| 模块 | 说明 |
|------|------|
| LLM 抽象层 | `LLMProvider` 统一接口，`AnthropicProvider` + `OpenAIProvider` 双实现 |
| Agent Loop | `AgentLoop` ReAct 循环，模型自主多轮调工具，20 轮上限 + 5 种停止条件 |
| 事件总线 | `EventBus` + 8 种 `AgentEvent`，AgentLoop/DialogueManager 发事件，TUI 订阅消费 |
| Plan Mode | `/plan` 切换只读工具集（调研模式），`/do` 恢复全量工具（执行模式） |
| 工具系统 | `Tool` 接口（含 `isReadOnly`） + `ToolRegistry` + `ToolExecutor` 超时执行 |
| 六个核心工具 | `read_file` / `write_file` / `edit_file` / `execute_command` / `find_files` / `search_content` |
| 分批工具执行 | 只读工具并发、副作用工具串行，结果按原始顺序回灌 |
| 流式双路收集 | `DialogueManager.doRound()` 内部一边推 EventBus 渲染终端，一边积攒写历史 |
| Token 用量追踪 | 流式实时累计 + 每轮 API 精确修正，终端底部状态栏显示 |
| 对话编排 | `DialogueManager` 管理历史，`doRound()` 封装 LLM 调用→收集→工具执行→写历史 |
| 终端 IO | JLine3 行编辑 + 历史翻找 + 命令确认对话框 + 工具进度实时显示 |

### Out of Scope（v0.3 不做）

上下文压缩、对话持久化、权限系统、工具执行沙箱、跨会话记忆、MCP 集成、代码高亮、Markdown 渲染、多会话切换

## 效果预览

```
> 帮我搜一下项目里所有提到 Anthropic 的 Java 文件，然后读第一个

好的，让我来搜索                              ← 第一轮流式输出
  ↑120 ↓60 · 累计 180
  search_content "Anthropic" ✓ 45ms           ← 工具执行

  find_files "*.java" ✓ 30ms

找到 3 个文件，我来读第一个                     ← 第二轮（模型自动继续）
  ↑350 ↓80 · 累计 610
  read_file AnthropicProvider.java ✓ 3ms

这个文件是 Anthropic API 的 Provider 实现...    ← 第三轮，模型总结完成
  ↑2.1k ↓400 · 累计 3.1k
```

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+

### 构建

```bash
git clone https://github.com/MySoulForYou/bingtangcode.git
cd bingtangcode
mvn package -DskipTests
```

### 配置

```bash
cp config.example.yaml config.yaml
```

编辑 `config.yaml`，填入你的 API Key：

```yaml
provider: anthropic          # 或 openai

anthropic:
  api_key: "sk-ant-xxxxx"
  model: claude-opus-4-7

openai:
  api_key: "sk-xxxxx"
  model: gpt-4o

agent:
  max_iterations: 20         # Agent Loop 最大迭代次数
```

### 运行

```bash
java -jar target/bingtangcode-0.3.0.jar
```

### 命令

| 命令 | 说明 |
|------|------|
| `/plan` | 切换到 Plan Mode，仅可用只读工具（调研阶段） |
| `/do` | 切换到 Do Mode，恢复全量工具（执行阶段） |
| `/help` | 显示帮助 |
| `/exit` 或 `/quit` | 退出 |

## 项目结构

```
src/main/java/com/bingtangcode/
├── Main.java                    # 入口，组装所有组件 + system prompt
├── config/
│   └── ConfigManager.java       # YAML 配置读取（含 maxIterations）
├── core/
│   ├── SessionManager.java      # REPL 主循环，解析 /plan /do /exit
│   ├── DialogueManager.java     # 对话历史 + doRound() 单轮执行
│   └── RoundResult.java         # doRound 返回结果
├── agent/
│   ├── AgentLoop.java           # ReAct 循环控制器
│   ├── AgentEvent.java          # 8 种事件类型定义
│   ├── AgentEventListener.java  # 监听器接口（全部 default 空实现）
│   └── EventBus.java            # 事件总线（CopyOnWriteArrayList）
├── llm/
│   ├── LLMProvider.java         # LLM 抽象接口
│   ├── Message.java             # 消息模型（含 toolCalls/toolResults）
│   ├── Role.java                # 角色枚举
│   ├── StreamCallback.java      # 流式回调（含 onToolCall/onReasoning/onUsage）
│   └── LLMimpl/
│       ├── AnthropicProvider.java   # Claude API + thinking SSE 解析
│       └── OpenAIProvider.java      # GPT API + reasoning SSE 解析
├── tool/
│   ├── Tool.java                # 工具接口（name/description/schema/execute/isReadOnly）
│   ├── ToolCall.java            # 一次工具调用
│   ├── ToolResult.java          # 执行结果
│   ├── ToolRegistry.java        # 工具注册中心
│   ├── ToolExecutor.java        # 超时执行器（Future.get 30s）
│   └── tools/
│       ├── ReadFileTool.java         # 读文件（isReadOnly=true）
│       ├── WriteFileTool.java        # 写文件
│       ├── EditFileTool.java         # 精确文本替换
│       ├── ExecuteCommandTool.java   # 执行 shell 命令（含确认对话框）
│       ├── FindFilesTool.java        # glob 搜索文件（isReadOnly=true）
│       └── SearchContentTool.java    # grep 搜索内容（isReadOnly=true）
└── tui/
    ├── TerminalIO.java          # 终端输入输出（JLine3 + ANSI）
    ├── TuiEventListener.java    # 事件→终端渲染翻译器
    └── BuddyManager.java        # Buddy 管理
```

## 技术栈

- **语言**: Java 21
- **构建**: Maven + maven-shade-plugin（fat jar）
- **终端**: JLine3（行编辑、历史、信号处理）
- **HTTP**: OkHttp 4
- **JSON/YAML**: Jackson
- **LLM API**: Anthropic Messages API / OpenAI Chat Completions API（SSE 流式）
- **架构**: 事件驱动（EventBus + sealed interface 事件类型）

## 许可证

MIT
