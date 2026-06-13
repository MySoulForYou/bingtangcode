# bingtangCode

终端 AI 编程助手 — 在命令行里与 AI 对话，让它自主调用工具完成编程任务。

## 项目目标

构建一个类 Claude Code 的终端 AI 编程助手。

- 支持多 LLM provider（Anthropic / OpenAI），通过配置文件切换
- ReAct Agent Loop：模型自主思考→调工具→观察结果→再思考，多轮循环直到任务完成
- 结构化系统提示：7 模块按职责组装，稳定内容走 SYSTEM 可缓存，动态内容走消息通道
- Plan Mode 运行时注入：通过 `<system-reminder>` 标签按轮次注入模式提醒，控制频率和详略
- 流式输出，AI 回复逐字呈现，推理过程可配置显示
- 六个核心工具：读文件、写文件、编辑文件、执行命令、搜索文件、搜索内容
- 五层防御权限系统：黑名单→路径沙箱→规则引擎→模式兜底→人在回路
- 事件驱动架构，模块完全解耦
- Java 21，终端原生体验

## 当前进度

**v0.5 — 五层防御权限系统** 已完成：

| 模块 | 说明 |
|------|------|
| PermissionMode | 四档权限模式（Default / AcceptEdits / Plan / Bypass），控制默认行为和工具过滤 |
| Blacklist | 19 条高危命令正则硬编码，不可绕过，任何模式下均生效 |
| PathSandbox | 统一路径校验 + `toRealPath()` 符号链接解析，5 个文件工具共用 |
| RuleEngine | 三层 YAML 规则匹配（local > project > user），glob 模式 + deny 优先 |
| PermissionGate | 五层防御编排（黑名单→沙箱→规则→模式→人在回路），集中拦截 |
| PermissionPrompt | 人在回路终端 UI，箭头键动态选择「允许本次/永久允许/拒绝本次」 |
| 规则持久化 | ALLOW_FOREVER 写入 `permissions.local.yaml`，下次自动生效 |
| 模式合并 | AgentLoop.Mode 统一为 PermissionMode，`/mode` 菜单 + Shift+Tab 切换 |
| 连续拒绝保护 | 连续 5 轮全工具被权限拒绝则触发 PERMISSION_DENIED_LOOP 终止 |

### v0.4 — 系统提示工程

| 模块 | 说明 |
|------|------|
| SystemPromptBuilder | 7 个固定模块按优先级组装（身份→系统约束→任务模式→动作执行→工具使用→语气风格→文本输出）+ 环境信息，稳定指令可缓存 |
| SystemReminderManager | Plan 模式提醒按 Agent Loop 轮次注入，首轮完整 + 每 3 轮重复 + 其余精简，`/do` 停止注入 |
| AgentLoop 集成 | 每轮 doRound 前注入提醒，模式切换时通知，`<system-reminder>` 消息不走 API 缓存前缀 |
| 工具描述双重强化 | EditFileTool + WriteFileTool 描述中追加关键约束，与系统提示互补 |
| 推理显示可配置 | `show_reasoning` 配置项控制是否灰显推理过程，false 时静默跳过 |
| 版本号软编码 | pom.xml 定义版本，Maven 资源过滤注入，启动时读取显示 |

### v0.3 — Agent Loop

| 模块 | 说明 |
|------|------|
| Agent Loop | `AgentLoop` ReAct 循环，模型自主多轮调工具，20 轮上限 + 6 种停止条件 |
| 事件总线 | `EventBus` + 8 种 `AgentEvent`，AgentLoop/DialogueManager 发事件，TUI 订阅消费 |
| Plan Mode | `/plan` 切换只读工具集（调研模式），`/do` 恢复全量工具（执行模式） |
| 工具系统 | `Tool` 接口（含 `isReadOnly`） + `ToolRegistry` + `ToolExecutor` 超时执行 |
| 六个核心工具 | `read_file` / `write_file` / `edit_file` / `execute_command` / `find_files` / `search_content` |
| 分批工具执行 | 只读工具并发、副作用工具串行，结果按原始顺序回灌 |
| 流式双路收集 | `DialogueManager.doRound()` 内部一边推 EventBus 渲染终端，一边积攒写历史 |
| Token 用量追踪 | 流式实时累计 + 每轮 API 精确修正，终端底部状态栏显示 |

### Out of Scope（当前不做）

上下文压缩、对话持久化、工具执行沙箱、跨会话记忆、MCP 集成、代码高亮、Markdown 渲染、多会话切换、项目指令文件加载、自动记忆、自动化评估、网络请求限制、资源配额、审计日志

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
provider: anthropic              # 或 openai

anthropic:
  api_key: "sk-ant-xxxxx"
  model: claude-opus-4-7

openai:
  api_key: "sk-xxxxx"
  model: gpt-4o
  # endpoint: https://api.openai.com/v1/chat/completions  # 可选，也支持 DeepSeek 等兼容接口

agent:
  max_iterations: 20             # Agent Loop 最大迭代次数
```

### 权限配置（可选）

三层 YAML 规则文件，优先级 local > project > user：

```bash
# 用户全局（所有项目生效）— 首次启动自动创建
~/.bingtangcode/permissions.yaml

# 项目共享（可提交 Git）
<project>/.bingtangcode/permissions.yaml

# 项目本地（不提交 Git，ALLOW_FOREVER 写入此处）
<project>/.bingtangcode/permissions.local.yaml
```

### 运行

```bash
java -jar target/bingtangcode-0.3.0.jar
```

### 命令

| 命令 | 说明 |
|------|------|
| `/plan` | 切换到 Plan Mode，仅可用只读工具 |
| `/do` | 切回 Default Mode，全工具可用 |
| `/mode` | 打开权限模式选择菜单（箭头键选择） |
| Shift+Tab | 循环切换四档权限模式 |
| `/help` | 显示帮助 |
| `/clear` | 清除屏幕 |
| `/exit` 或 `/quit` | 退出 |

## 项目结构

```
src/main/java/com/bingtangcode/
├── Main.java                    # 入口，组装所有组件
├── config/
│   └── ConfigManager.java       # YAML 配置读取
├── core/
│   ├── SessionManager.java      # REPL 主循环，命令解析，/mode 菜单
│   ├── DialogueManager.java     # 对话历史 + doRound() 单轮执行 + 权限检查
│   ├── RoundResult.java         # doRound 返回结果（含 allPermissionDenied）
│   ├── SystemPromptBuilder.java # 7 模块系统提示组装 + EnvInfo
│   └── SystemReminderManager.java  # Plan 模式提醒频率控制
├── agent/
│   ├── AgentLoop.java           # ReAct 循环控制器 + PermissionModeProvider
│   ├── AgentEvent.java          # 8 种事件类型 + 6 种停止原因
│   ├── AgentEventListener.java  # 监听器接口
│   └── EventBus.java            # 事件总线
├── permission/
│   ├── PermissionMode.java      # 四档模式枚举 + DefaultAction
│   ├── PermissionAction.java    # ALLOW / DENY
│   ├── PermissionRule.java      # 规则 record
│   ├── PermissionResult.java    # 检查结果 record
│   ├── ToolFriendlyName.java    # 内部名→友好名 + 主参数提取
│   ├── Blacklist.java           # 19 条高危命令正则
│   ├── PathSandbox.java         # 路径校验 + 符号链接解析
│   ├── PathViolationException.java
│   ├── RuleEngine.java          # 三层规则匹配（local > project > user）
│   ├── PermissionGate.java      # 五层防御编排
│   ├── PermissionPrompt.java    # 人在回路终端 UI（箭头键选择）
│   ├── PermissionConfigLoader.java  # 三层 YAML 加载 + 首次启动自动创建
│   ├── PermissionConfigManager.java  # ALLOW_FOREVER 持久化
│   ├── AskResult.java           # 人在回路结果枚举
│   ├── HumanInTheLoopHandler.java    # 人在回路接口
│   └── PermissionModeProvider.java   # 动态模式获取接口
├── llm/
│   ├── LLMProvider.java
│   ├── Message.java
│   ├── Role.java
│   ├── StreamCallback.java
│   └── LLMimpl/
│       ├── AnthropicProvider.java
│       └── OpenAIProvider.java
├── tool/
│   ├── Tool.java
│   ├── ToolCall.java
│   ├── ToolResult.java
│   ├── ToolRegistry.java
│   ├── ToolExecutor.java
│   └── tools/
│       ├── ReadFileTool.java
│       ├── WriteFileTool.java
│       ├── EditFileTool.java
│       ├── ExecuteCommandTool.java
│       ├── FindFilesTool.java
│       └── SearchContentTool.java
└── tui/
    ├── TerminalIO.java          # 终端输入输出（JLine3 + 状态栏 + 读键）
    ├── TuiEventListener.java    # 事件→终端渲染
    └── BuddyManager.java
```

## 技术栈

- **语言**: Java 21
- **构建**: Maven + maven-shade-plugin（fat jar）
- **终端**: JLine3（行编辑、历史、信号处理、raw mode 读键）
- **HTTP**: OkHttp 4
- **JSON/YAML**: Jackson
- **LLM API**: Anthropic Messages API / OpenAI Chat Completions API（SSE 流式）
- **架构**: 事件驱动（EventBus + sealed interface 事件类型）

## 许可证

MIT
