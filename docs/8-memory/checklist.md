# Checklist: BingtangCode 记忆系统验收清单

本清单包含记忆系统各项能力的具体验收项，每一项必须是**可勾选、可观测**的。

---

## 1. 项目指令文件加载与安全沙箱验证
- [ ] 在项目根目录下创建 `BINGTANGCODE.md`，本地 `.bingtangcode/BINGTANGCODE.md`，用户目录 `~/.bingtangcode/BINGTANGCODE.md`。运行加载后，验证控制台输出的 Prompt 上下文按照“项目根目录 $\rightarrow$ 本地 `.bingtangcode/` $\rightarrow$ 用户 `~/.bingtangcode/`”的顺序以 `---` 拼接，且高优先级排在前面。
- [ ] 在 `BINGTANGCODE.md` 中编写一个循环引用的 `@include` 链（如 A 引用 B，B 引用 A），运行后程序不产生死循环，而是报错或跳过，不发生 StackOverflow。
- [ ] 创建嵌套深度为 6 层的 `@include` 关系，验证第 6 层文件未被解析，且在加载后的拼接指令中包含占位警告注释：`<!-- @include 嵌套层级超出限制，已跳过 -->` 或类似警告。
- [ ] 在项目根目录的 `BINGTANGCODE.md` 中写入 `@include ../../etc/passwd`（试图逃逸项目根边界），验证系统未崩溃，且在拼接指令中出现精准注释：`<!-- @include 路径超出允许范围，已跳过: ../../etc/passwd -->`。
- [ ] 在用户级 `~/.bingtangcode/BINGTANGCODE.md` 中写入 `@include ../other_user/secret`（试图逃逸用户根边界），验证系统未崩溃，且包含跳过警告注释。

## 2. 会话持久化与动态属性校验
- [ ] 启动新会话并输入一段对话，验证 `.bingtangcode/sessions/` 目录下生成了一个名为 `YYYYMMDD-HHMMSS-xxxx.jsonl`（首部 15 位为时间戳，尾部 4 位为十六进制随机数）的文件。
- [ ] 打开生成的 `.jsonl` 文件，验证每条消息为独立的单行 JSON 对象，且最后一行是完整的 JSON。
- [ ] 构造一个包含有多条消息的 `session.jsonl`，第一条 `role="user"` 消息的 `content` 为一长串字符（如 100 字）。运行 `/session list`，验证控制台打印出的会话标题为该条消息的前 47 个字符加上省略号 `...`（总长度正好 50 个字符）。

## 3. 会话恢复异常修复与时效性校验
- [ ] 手动往 `.jsonl` 中追加一行格式损坏的 JSON（如丢失右括号），重新打开会话，验证程序正常启动，跳过了损坏行且未抛出异常。
- [ ] 在 `.jsonl` 文件末尾人工追加一条 `role="assistant"` 消息，该消息包含 `tool_calls` 请求，但不追加对应的 `tool_results` 行。启动会话，验证会话历史被自动截断，丢弃了该条无响应的 `tool_calls` 消息，使加载的历史符合模型调用要求。
- [ ] 修改 `.jsonl` 文件的消息时间戳，使两条连续的消息发送间隔为 8 小时。启动会话，验证系统自动在两条消息之间插入了一条角色为 `user` 的提示消息，其内容精确为：
  `"[系统提示] 本会话已暂停 8 小时 0 分钟。部分上下文可能已过时，如需最新信息请重新读取相关文件。"`。
- [ ] 创建一个文件名时间戳为 31 天前的 `.jsonl` 会话文件，启动 MewCode，验证该会话文件在后台被自动执行删除。
- [ ] 设置 `config.yaml` 的 `context_window` 为较小数值（如 8000 Tokens），加载一个拥有 10000 Tokens 历史的会话文件，验证启动恢复时，控制台输出 `[系统] 正在进行对话历史压缩...` 并成功执行了 `ContextCompressor.compressHistory` 一次。

## 4. 自动笔记与记忆更新校验
- [ ] 发送一条不需要工具调用的消息并等待大模型返回最终文本（结束一轮 Agent Loop），验证后台异步发起了 LLM 提炼请求。
- [ ] 检查 `.bingtangcode/memory/`（项目级）和 `~/.bingtangcode/memory/`（用户级）目录，验证生成了格式为 `<type>_<short_slug>.md`（全小写，下划线分隔，如 `user_preference_terse_replies.md`）的 Markdown 笔记文件，且文件包含完整的 Frontmatter。
- [ ] 检查 `.bingtangcode/memory/MEMORY.md` 索引文件，验证其行数不超过 200 行，文件体积小于 25KB，且当条目接近 200 行时，大模型在更新时自动执行了旧条目的合并或淘汰。
- [ ] 验证缓存稳定性：指令与记忆索引仅在会话启动初始化时载入并注入 System Prompt。在会话交互过程中，手动修改外部 `BINGTANGCODE.md` 文件或触发后台异步更新 `MEMORY.md` 后，后续请求中发送给 LLM API 的首条 `SYSTEM` 提示词内容依然保持原样不变，以确信 Prompt Cache 不会被破坏。

## 5. 端到端集成验收
- [ ] **完整流程测试**：
  1. 启动 BingtangCode。
  2. 发送一个需要写文件的请求，在大模型发出 `write_file` 的 `tool_use` 请求后，立即强杀（Kill）BingtangCode 进程，模拟异常中断。
  3. 再次启动 BingtangCode。
  4. 验证会话列表显示正常，且标题是由强杀前的首条 user 消息截断而来（不超过 50 字）。
  5. 进入该会话，输入新的命令，大模型能够正常调用工具并回复，无任何 API 格式错误报错。
  6. 检查 `MEMORY.md` 已被正确注入并更新。
