# bingtangCode — Spec v0.1

## 背景
构建一个终端 AI 编程助手。第一阶段的唯一目标：让 AI 在终端里开口说话。

## 目标用户
在终端里工作的开发者，想用自然语言与 AI 对话并获得代码建议。

## 能力清单

1. 启动后进入交互式对话 REPL，终端显示欢迎信息和 prompt 提示符
2. 用户输入一行文本后按回车发送，AI 以流式方式实时输出回复
3. 支持多 provider：Anthropic Claude API、OpenAI API，通过配置文件切换
4. LLM provider 层抽象为统一接口，不同 provider 各自实现，新增 provider 不影响对话管理层
5. 对话历史保留在内存中，每次请求前对历史做格式清洗（合并连续同角色、校验 USER/ASSISTANT 交替），清洗后携带完整上下文发给 LLM
6. 用户输入支持行内编辑（左右移动、删除、插入），支持历史翻找（上/下箭头）
7. AI 流式回复过程中用户可按 Ctrl+C 中断
8. 用户输入与 AI 回复使用不同颜色/前缀区分
9. API Key 和 provider 配置从文件读取，不硬编码
10. 提供 `/exit` 或 `/quit` 命令退出程序

## 非功能要求

- Java 21，Maven 构建
- 依赖：JLine3（终端输入）、OkHttp 或 Java 11 HttpClient（HTTP）
- 终端输出使用 ANSI 转义序列控制颜色/样式，不引入额外终端库
- 配置文件路径：项目根目录 `config.yaml`，YAML 格式。提供 `config.example.yaml` 模板。`max_tokens` 默认 32768，仅对 Anthropic 生效（API 必填），OpenAI 请求体中不传此参数
- 启动时不依赖网络连接，仅在用户发出第一条消息时建立 API 连接

## 设计骨架

```
入口 (Main)
 └─ 会话管理器 (SessionManager)     — REPL 循环，调度输入/输出
      ├─ 终端接口 (TerminalIO)      — JLine3 输入 + ANSI 输出
      ├─ 对话管理器 (DialogueManager) — 消息历史维护，上下文组装
      └─ LLM 抽象层 (LLMProvider)   — 统一流式接口
           ├─ AnthropicProvider     — Claude API 实现
           └─ OpenAIProvider        — GPT API 实现
```

## Out of Scope（第一版不做）

- Tool use / Function calling
- 对话持久化到磁盘
- 多行编辑器模式
- 代码语法高亮
- Markdown 渲染
- 文件系统操作
- MCP 集成
- 会话管理（多会话切换）
- 自动补全
