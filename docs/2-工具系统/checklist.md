# checklist.md · 工具系统

## 工具抽象层

- [ ] `grep -r "interface Tool" src/` 返回 `Tool.java`，包含 `getName()`、`getDescription()`、`getParametersSchema()`、`execute(Map)` 四个方法
- [ ] `grep -r "record ToolResult" src/` 返回 ToolResult 定义，字段含 `toolCallId`、`content`、`isError`
- [ ] `grep -r "record ToolCall\|class ToolCall" src/` 返回 ToolCall 定义，字段含 `id`、`name`、`parameters`
- [ ] `grep -r "toolCalls" src/main/java/com/bingtangcode/llm/Message.java` 返回该字段声明
- [ ] `grep -r "toolResults" src/main/java/com/bingtangcode/llm/Message.java` 返回该字段声明
- [ ] 已有代码 `new Message(Role.USER, "hello")` 编译通过（向下兼容构造器）

## 工具注册中心

- [ ] `grep -r "class ToolRegistry" src/` 返回注册中心文件
- [ ] ToolRegistry.register() 同名重复注册抛异常（用 main 临时代码验证）
- [ ] ToolRegistry.get("read_file") 返回非 null，get("nonexistent") 返回 null
- [ ] ToolRegistry.getAll() 返回 6 个元素

## 六个核心工具

- [ ] read_file: 请求读存在的文件，返回内容含文件文本；请求不存在的文件，返回 isError=true 含 "文件不存在"
- [ ] read_file: 带 startLine/endLine 参数，只返回指定行范围
- [ ] write_file: 写入不存在的文件，文件被创建且内容正确；写入已存在的文件，内容被覆盖
- [ ] edit_file: oldString 唯一匹配，文件被正确替换（`grep oldStr file` 返回 0 条，`grep newStr file` 返回 ≥1 条）
- [ ] edit_file: oldString 不存在，返回 isError=true，error 含 "未找到匹配文本"
- [ ] edit_file: oldString 出现 3 次，返回 isError=true，error 含 "匹配到 3 处"
- [ ] execute_command: `echo hello` 返回 stdout="hello\n"，exitCode=0
- [ ] execute_command: `exit 1` 返回 exitCode=1
- [ ] execute_command: 包含 stderr 的命令（如 `ls /nonexistent 2>&1`），返回结果含 stderr 内容
- [ ] find_files: 按 `*.java` 搜索，返回 ≥1 条结果；按 `*.nonexistent` 搜索，返回 0 条
- [ ] find_files: 结果 ≤200 条（在项目根搜 `*` 验证截断）
- [ ] search_content: 搜索 "Tool" 返回 ≥1 条结果，格式为 `文件:行号: 内容`
- [ ] search_content: 搜索 "xyznonexistent123" 返回 0 条

## 工具执行器

- [ ] `grep -r "class ToolExecutor" src/` 返回执行器文件
- [ ] 正常工具调用返回的 ToolResult.isError=false，toolCallId 与传入一致
- [ ] 执行超时（如 `sleep 999` 命令）返回 ToolResult.isError=true，content 含 "超时"
- [ ] 工具内部抛异常返回 ToolResult.isError=true，content 含异常信息
- [ ] `grep -r "toolTimeout\|tool_timeout" src/` 返回配置读取和 config.example.yaml 中的配置项

## 流式工具调用解析

- [ ] AnthropicProvider: 请求体包含 `"tools": [...]` 字段
- [ ] AnthropicProvider: SSE content_block_start (type=tool_use) 被正确解析
- [ ] AnthropicProvider: SSE input_json_delta 片段正确拼接为完整 JSON
- [ ] OpenAIProvider: 请求体包含 `"tools": [...]` 字段
- [ ] OpenAIProvider: delta.tool_calls 片段按 index 正确累积
- [ ] 两个 Provider 在工具调用解析完成后均回调 `StreamCallback.onToolCall(ToolCall)`

## TUI 命令确认对话框

- [ ] execute_command 执行前，终端显示高亮的命令文本（ANSI 黄色）
- [ ] 终端显示 `[y/N]` 提示且等待用户输入
- [ ] 用户输入 `y` 命令继续执行，返回正常结果
- [ ] 用户输入 `n` 命令被拒绝，ToolResult 返回 isError=true 含 "用户取消了命令执行"
- [ ] 10 秒无输入自动返回 false（命令被拒绝）

## 对话流程

- [ ] 用户输入触发模型返回 tool_call 时，assistant 消息（含 tool_calls）被加入历史
- [ ] 工具执行后 tool_result 作为 user 消息注入历史
- [ ] 第二轮 streamChat 能基于 tool_result 生成最终文本回复
- [ ] 模型第二轮又返回 tool_call 时，不再执行，回复 "本轮仅支持一次工具调用"

## LLMProvider 接口

- [ ] `streamChat()` 签名包含 `List<Tool> tools` 参数
- [ ] 两个实现类（AnthropicProvider、OpenAIProvider）均更新签名且编译通过

## 主流程装配

- [ ] 启动日志输出 "已注册 N 个工具"（N=6）
- [ ] `config.example.yaml` 含 `tool.timeout_seconds: 30` 配置
- [ ] `mvn clean compile` 返回 BUILD SUCCESS
- [ ] 文件工具的构造函数接收 `Path projectRoot` 参数（`grep "Path projectRoot" src/main/java/com/bingtangcode/tool/tools/` 返回 ≥4 条）
- [ ] ExecuteCommandTool 构造函数接收 `Function<String, Boolean>` 参数（`grep "Function<String, Boolean>" src/main/java/com/bingtangcode/tool/tools/ExecuteCommandTool.java` 返回结果）
- [ ] Main.java 中 ExecuteCommandTool 注册时传入 `terminalIO::confirmCommand`（`grep "terminalIO::confirmCommand" src/main/java/com/bingtangcode/Main.java` 返回结果）

## 端到端验证

- [ ] 启动 bingtangCode，输入 "帮我读一下 pom.xml 的内容"，模型调用 read_file，终端输出 pom.xml 内容，模型给出文本总结
- [ ] 输入 "创建一个文件 hello.txt 内容是 Hello World"，模型调用 write_file，文件被创建
- [ ] 输入 "把 hello.txt 里的 World 改成 bingtangCode"，模型调用 edit_file，文件内容更新为 "Hello bingtangCode"
- [ ] 输入 "帮我搜一下项目里所有包含 'Tool' 的 Java 文件"，模型调用 search_content，返回匹配列表
- [ ] 输入 "列出项目里所有的 java 文件"，模型调用 find_files，返回文件列表
- [ ] 输入 "执行 ls -la"，TUI 弹出确认框，按 y 后显示目录列表
- [ ] 输入 "执行 rm -rf /"，TUI 弹出确认框，按 n 后命令被拒绝，模型收到 "用户取消了命令执行"
- [ ] 构造超时命令（`sleep 999`），确认执行后模型收到超时错误并给出说明
- [ ] edit_file 输入一个会匹配多处的内容（如修改一个常见单词），模型收到 "匹配到 N 处" 错误并给出建议
