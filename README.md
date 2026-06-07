# bingtangCode

终端 AI 编程助手 — 在命令行里与 AI 对话，获得编程帮助。

## 项目目标

构建一个类 Claude Code 的终端 AI 编程助手，**第一阶段唯一目标：让 AI 在终端里开口说话**。

- 支持多 LLM provider（Anthropic / OpenAI），通过配置文件切换
- 流式输出，AI 回复逐字呈现
- 完整的上下文对话记忆
- Java 21，终端原生体验

## 当前进度

**v0.1 — 多轮对话** 已完成：

| 模块 | 说明 |
|------|------|
| LLM 抽象层 | `LLMProvider` 统一接口，`AnthropicProvider` + `OpenAIProvider` 双实现 |
| 配置管理 | YAML 配置文件 `config.yaml`，含 API Key、模型、端点等 |
| 对话管理 | 消息历史维护、格式清洗（合并同角色、校验交替）、上下文组装 |
| 终端 IO | JLine3 行编辑 + 历史翻找 + ANSI 彩色输出 |
| 会话控制 | REPL 主循环、Ctrl+C 中断流式输出、`/exit` 退出 |

### Out of Scope（v0.1 不做）

Tool use、对话持久化、多行编辑、代码高亮、Markdown 渲染、文件系统操作、MCP 集成、多会话切换、自动补全

## 效果预览

```
  bingtangCode - 终端 AI 编程助手
  当前模型: Anthropic claude-opus-4-7
  输入 /exit 退出

  > 什么是 Java record?
  → 什么是 Java record?

  🤖 Java record 是 Java 14 引入的一种特殊类，用于简洁地
     定义不可变数据载体。它会自动生成构造器、equals()、
     hashCode() 和 toString() 方法...

  > 帮我写一个 record 示例
  → 帮我写一个 record 示例

  🤖 当然，这是一个简单的 Person record：

     public record Person(String name, int age) {
         public Person {
             if (age < 0) {
                 throw new IllegalArgumentException("年龄不能为负");
             }
         }
     }

  > /exit
  再见！
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
java -jar target/bingtangcode-0.1.0.jar
```

## 项目结构

```
src/main/java/com/bingtangcode/
├── Main.java                  # 入口，组装所有组件
├── config/
│   └── ConfigManager.java     # YAML 配置读取
├── core/
│   ├── SessionManager.java    # REPL 主循环
│   └── DialogueManager.java   # 对话历史与上下文管理
├── llm/
│   ├── LLMProvider.java       # LLM 抽象接口
│   ├── Message.java           # 消息模型
│   ├── Role.java              # 角色枚举
│   ├── StreamCallback.java    # 流式回调接口
│   └── LLMimpl/
│       ├── AnthropicProvider.java  # Claude API
│       └── OpenAIProvider.java     # GPT API
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
