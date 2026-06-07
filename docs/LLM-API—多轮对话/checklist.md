# bingtangCode — Checklist v0.1

## 项目骨架
- [ ] `mvn compile` 无报错通过
- [ ] `mvn package` 产出可执行 JAR
- [ ] `grep -r "21" pom.xml` 找到 `<release>21</release>` 或等效配置

## 终端 IO
- [ ] 启动程序后终端显示欢迎信息（包含 "bingtangCode" 字样）
- [ ] 输入 prompt 以 `>` 或 `→` 等符号开头，与 AI 回复视觉区分
- [ ] 用户输入内容与 AI 回复颜色不同（`grep "ANSI" src` 返回 ≥ 2 条）
- [ ] 按上箭头能召回上一次输入
- [ ] 输入 `/exit` 或 `/quit` 程序正常退出（exit code 0）
- [ ] 空输入（直接按回车）不发送给 LLM

## LLM 流式输出
- [ ] 用户输入一句话 → AI 回复逐字出现在终端，有明显 streaming 效果（非一次性输出完整回复）
- [ ] Anthropic provider：发送消息后能收到有效 token 流
- [ ] OpenAI provider：发送消息后能收到有效 token 流
- [ ] `grep -r "text/event-stream\|SSE\|Server-Sent" src` 返回 ≥ 1 条（证明解析了 SSE）

## 中断处理
- [ ] AI 流式输出过程中按 Ctrl+C，输出立即停止且程序未退出
- [ ] Ctrl+C 中断后显示简短提示（如 "^C" 或 "已中断"）
- [ ] 中断后可继续输入下一轮对话

## 配置
- [ ] `grep -r "config.yaml" src` 返回 ≥ 2 条
- [ ] `config.example.yaml` 文件存在于项目根目录
- [ ] `.gitignore` 包含 `config.yaml`
- [ ] 配置文件中 `provider=anthropic` 时，程序使用 Anthropic API
- [ ] 配置文件中 `provider=openai` 时，程序使用 OpenAI API
- [ ] 配置文件不存在时，程序打印引导信息告知用户如何创建（含 `cp config.example.yaml config.yaml` 提示）
- [ ] API key 为空时，程序明确提示缺少 key 并退出
- [ ] `max_tokens` 配置仅对 Anthropic 生效，OpenAI 请求体中不含此字段
- [ ] `grep "buildApiMessages" src` 返回 ≥ 1 条（证明格式转换层存在）
- [ ] 自定义 `endpoint` 值（如 Ollama 地址）在 API 请求中生效

## 对话历史
- [ ] 连续 3 轮对话（Q1→A1→Q2→A2→Q3→A3），第三轮 LLM 能正确理解前两轮上下文
- [ ] `grep "List<Message>" src` 返回 ≥ 1 条（证明消息历史是列表结构）
- [ ] `grep "合并\|merge\|并成一条" src` 返回 ≥ 1 条（证明历史会合并连续同角色消息）
- [ ] 尝试构造连续两条 USER 消息的历史，验证合并行为正确

## 端到端
- [ ] 从终端执行 `java -jar target/bingtangcode-*.jar` 启动
- [ ] 输入 "Hello, who are you?" → 收到 AI 流式回复 → 继续输入 "What did I just ask?" → AI 回答提到第一轮的问题内容
- [ ] 配置 Anthropic → 对话一轮 → 退出 → 改配置为 OpenAI → 重新启动 → 对话一轮（两次均成功）
