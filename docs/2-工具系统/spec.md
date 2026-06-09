# spec.md · 工具系统

## 背景

bingtangCode v0.1 完成了多轮对话——用户输入文本，模型流式返回文本。模型只能"动嘴"不能"动手"：它读不了项目文件、改不了代码、搜不了代码库、执行不了命令。

本章给 bingtangCode 装上工具系统：定义统一的工具接口，让模型能选择工具并传参，由 bingtangCode 执行，把结果喂回模型。模型据此判断结果是否成功、是否需要调整后重试。

## 目标用户

在终端里用 bingtangCode 做编程任务的开发者。

## 能力清单

1. **统一工具接口** — 每个工具实现同一接口，暴露名称、描述、参数 JSON Schema、执行方法
2. **工具注册中心** — 启动时手动注册工具实例，按名查找；各 LLM 客户端直接从 Tool 提取字段并适配为自己的 API 格式
3. **六个核心工具**
   - `read_file` — 读取指定文件内容，支持起止行号
   - `write_file` — 创建或覆盖写文件
   - `edit_file` — 原文唯一匹配替换，匹配不到或多处匹配均报错
   - `execute_command` — 执行 shell 命令，返回 stdout + stderr + exit code
   - `find_files` — 按 glob 模式搜索文件名
   - `search_content` — 按关键词/正则搜索文件内容，返回文件:行号:行内容
4. **流式工具调用解析** — LLM 客户端在流式响应中检测 tool_call 事件，碎片化 JSON 参数拼装完整后交给工具执行器
5. **工具执行器** — 统一入口，对每次工具调用施加超时上限，捕获异常，失败信息以结构化格式返回给模型
6. **结果回灌** — 工具执行结果（tool_result）注入对话历史，模型据此生成最终回复或给出错误说明
7. **openai+anthropic 双兼容** — 内部定义最小公约数 ToolCall/ToolResult 模型，两个 LLM 客户端各自适配翻译
8. **命令确认** — `execute_command` 接收确认回调（`String → boolean`），执行前回调，用户 y/n 裁决；回调由 Main 装配时把 TerminalIO.confirmCommand 注入，工具层不依赖 TUI

## Out of Scope

- 多工具连环调用（Agent Loop）—— 模型拿到一次工具结果后不自动调下一个工具，本章只做单轮 tool_call → tool_result
- 工具执行沙箱（chroot/cgroup/容器隔离）
- 每个工具独立超时配置（统一超时值）
- 插件的工具注册机制（注解扫描、SPI）
- 工具调用重试、降级、超时后自动重试
- MCP 协议集成

## 非功能要求

- 工具执行不能阻塞终端 UI 响应
- 超时必须在执行器层面绝对生效（Future.get(timeout) + 超时后强制中断）
- 错误结果必须包含足够上下文让模型能自我纠错（文件名、行号、预期vs实际）
- edit_file 原文匹配对空白字符敏感，不自动 trim
- 所有操作文件的工具通过构造函数接收项目根目录，相对路径 resolve 到根目录下，拒绝越界访问

## 设计骨架

```
┌──────────────────────────────────────────────────────┐
│                    DialogueManager                    │
│  systemPrompt  →  [user] → [assistant + tool_calls]  │
│  → 执行工具  →  [user + tool_results] → [assistant]    │
└──────────┬────────────────────────┬──────────────────┘
           │                        │
  ┌────────▼────────┐    ┌─────────▼──────────┐
  │  ToolExecutor    │    │  LLMProvider       │
  │  .execute(tool,  │    │  .streamChat(      │
  │   params)        │    │   msgs,            │
  │  timeout+errors  │    │   List<Tool>,      │
  └────────┬─────────┘    │   callback)        │
           │              └────────────────────┘
  ┌────────▼────────┐
  │  ToolRegistry    │
  │  register(Tool)  │
  │  get(name)       │
  │  getAll()        │
  └────────┬─────────┘
           │
  ┌────────▼──────────────────────────────────┐
  │  Tool (interface)                          │
  │  + name, description, parametersSchema     │
  │  + execute(params): ToolResult             │
  ├────────────┬──────────┬──────────┬─────────┤
  │ read_file  │write_file│edit_file │exec_cmd │  ...
  └────────────┴──────────┴──────────┴─────────┘
```

| 类型 | 角色 |
|------|------|
| `Tool`（接口） | 工具的元信息（名称、描述、参数 Schema）+ 执行方法，每个具体工具如 ReadFileTool 实现它 |
| `ToolCall`（record） | LLM 发出的一次工具调用——id 标识本次调用，name 指向目标工具，parameters 是模型填入的参数 |
| `ToolResult`（record） | 执行结果——toolCallId 对应原始调用，content 是返回文本，isError 标记成功/失败 |

### 数据流（一次工具调用）

```
1. 用户输入 "把 README.md 里的 'install' 改成 'installation'"
2. DialogueManager 组装消息历史，调 LLMProvider.streamChat()
3. 模型在流式响应中输出 tool_call:
   {name:"edit_file", params:{filePath:"README.md", oldStr:"install", newStr:"installation"}}
4. LLMProvider 解析完 tool_call → 内部 ToolCall 对象 → StreamCallback.onToolCall(toolCall)
5. DialogueManager 收到回调，从 ToolRegistry 取到工具，调 ToolExecutor.execute(tool, params)
6. ToolExecutor.execute():
   a. Future 包装，30s 超时
   b. tool.execute(params) → ToolResult("已替换 README.md 第 5 行")
   c. 注入 toolCallId，异常/超时包装为 isError=true
7. ToolResult 作为 tool_result 注入对话历史
8. DialogueManager 再次调 streamChat()，模型生成最终文本回复
```
