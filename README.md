# bingtangCode

终端 AI 编程助手 — 在命令行里与 AI 对话，获得编程帮助。

## 项目目标

构建一个类 Claude Code 的终端 AI 编程助手。

- 支持多 LLM provider（Anthropic / OpenAI），通过配置文件切换
- 流式输出，AI 回复逐字呈现
- 模型可调用工具：读文件、写代码、搜索内容、执行命令
- Java 21，终端原生体验

## 当前进度

**v0.2 — 工具系统** 已完成：

| 模块 | 说明 |
|------|------|
| LLM 抽象层 | `LLMProvider` 统一接口，`AnthropicProvider` + `OpenAIProvider` 双实现 |
| 工具系统 | `Tool` 统一接口 + `ToolRegistry` 注册中心 + `ToolExecutor` 超时执行器 |
| 六个核心工具 | `read_file` / `write_file` / `edit_file` / `execute_command` / `find_files` / `search_content` |
| 双 API 工具调用 | Anthropic `tool_use` SSE 解析 + OpenAI `tool_calls` 解析，支持一次多工具 |
| 对话编排 | 调工具→执行→结果回灌→第二轮回复，完整往返 |
| 终端 IO | JLine3 行编辑 + 历史翻找 + 命令确认对话框 + 工具调用实时显示 |

### Out of Scope（v0.2 不做）

多轮连环工具调用、对话持久化、代码高亮、Markdown 渲染、MCP 集成、多会话切换、自动补全

## 效果预览

```
  ╭───────────────────────────╮
  │ ❯ 帮我读一下 pom.xml
  ╰─ main ────────────────────╯
  💭 思考 1s
    read_file pom.xml ✓ 3ms
  你的 pom.xml 定义了 bingtangCode 项目，依赖 JLine3、OkHttp、Jackson...

  ╭───────────────────────────╮
  │ ❯ 搜一下所有含 "Tool" 的 Java 文件
  ╰─ main ────────────────────╯
  💭 思考 2s
    search_content "Tool" *.java ✓ 12ms
  找到 5 处匹配：
    ToolRegistry.java:15: public class ToolRegistry {
    ToolExecutor.java:12: public class ToolExecutor {
    ...
```

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+

### 构建

```bash
git clone https://github.com/MySoulForYou/bingtangcode.git
cd bingtangcode
mvn package
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
```

### 运行

```bash
java -jar target/bingtangcode-0.2.0.jar
```

## 项目结构

```
src/main/java/com/bingtangcode/
├── Main.java                  # 入口，组装所有组件
├── config/
│   └── ConfigManager.java     # YAML 配置读取
├── core/
│   ├── SessionManager.java    # REPL 主循环
│   └── DialogueManager.java   # 对话历史 + 工具调用编排
├── llm/
│   ├── LLMProvider.java       # LLM 抽象接口
│   ├── Message.java           # 消息模型（含 toolCalls/toolResults）
│   ├── Role.java              # 角色枚举
│   ├── StreamCallback.java    # 流式回调（含 onToolCall）
│   └── LLMimpl/
│       ├── AnthropicProvider.java  # Claude API + tool_use SSE 解析
│       └── OpenAIProvider.java     # GPT API + tool_calls SSE 解析
├── tool/
│   ├── Tool.java              # 工具接口（name/description/schema/execute）
│   ├── ToolCall.java          # 一次工具调用
│   ├── ToolResult.java        # 执行结果
│   ├── ToolRegistry.java      # 工具注册中心
│   ├── ToolExecutor.java      # 超时执行器
│   └── tools/
│       ├── ReadFileTool.java       # 读文件
│       ├── WriteFileTool.java      # 写文件
│       ├── EditFileTool.java       # 精确文本替换
│       ├── ExecuteCommandTool.java # 执行 shell 命令
│       ├── FindFilesTool.java      # glob 搜索文件
│       └── SearchContentTool.java  # 搜索文件内容
└── tui/
    ├── TerminalIO.java        # 终端输入输出（JLine3 + ANSI）
    └── BuddyManager.java      # Buddy 管理
```

## 技术栈

- **语言**: Java 21
- **构建**: Maven + maven-shade-plugin（fat jar）
- **终端**: JLine3（行编辑、历史）
- **HTTP**: OkHttp 4
- **JSON/YAML**: Jackson
- **LLM API**: Anthropic Messages API / OpenAI Chat Completions API（SSE 流式）

## 许可证

MIT
