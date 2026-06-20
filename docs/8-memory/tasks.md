# Tasks: BingtangCode 记忆系统开发任务

本任务列表拆分了实现 BingtangCode 双层记忆系统的各个独立任务，按照依赖顺序编排，以确保开发过程的渐进式演进与高内聚性。

---

## 任务 1：设计并实现多层级指令加载器与 `@include` 安全解析逻辑
- **说明**：支持从项目根目录 `BINGTANGCODE.md`、本地私有目录 `.bingtangcode/BINGTANGCODE.md` 与用户目录 `~/.bingtangcode/BINGTANGCODE.md` 三个层级读取指令。实现 `@include` 标记解析，并建立沙箱安全校验、环路防死锁和 5 层最大深度限制。
- **影响文件**：
  - `src/main/java/com/bingtangcode/core/SystemReminderManager.java`（新增/重构指令加载逻辑）
- **依赖任务**：无
- **参考资料**：
  - [SystemReminderManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SystemReminderManager.java)
  - 路径安全与边界设计参考 [PathSandbox.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/permission/PathSandbox.java) 中的沙箱实现逻辑。

---

## 任务 2：重构会话持久化引擎：实现 JSONL 追加写入（WAL）
- **说明**：设计会话文件的写入策略，将会话以标准 JSONL 格式存储在 `.bingtangcode/sessions/` 目录下。每次完成用户消息、工具调用请求、工具返回结果或 Assistant 最终答复时，立即将对应的 `Message` 实例序列化为单行 JSON 并追加到文件末尾。
- **影响文件**：
  - `src/main/java/com/bingtangcode/core/SessionManager.java`
  - `src/main/java/com/bingtangcode/core/DialogueManager.java`（在 `doRound` 各个落盘节点调用追加写入）
- **依赖任务**：任务 1
- **参考资料**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java#L129-L236)（`doRound` 的核心循环）

---

## 任务 3：实现会话属性的无元数据动态计算逻辑
- **说明**：编写动态解析逻辑，扫描 `.bingtangcode/sessions/` 下的 `.jsonl` 文件。直接从文件名解析会话 ID 与创建时间，并通过流式读取文件的第一条 User 消息生成标题，读取最后一行获取最后活跃时间及消息总数。
- **影响文件**：
  - `src/main/java/com/bingtangcode/core/SessionManager.java`
- **依赖任务**：任务 2
- **参考资料**：
  - [SessionManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SessionManager.java#L52-L90)（关于命令如 `/session list` 的处理入口）

---

## 任务 4：实现会话加载时的坏行跳过与工具调用完整性校验（ValidateMessageChain）
- **说明**：在恢复会话读取 JSONL 时，捕获不合法的 JSON 行并跳过。同时实现消息链校验算法：维护 `pendingToolUses` 集合，逐条扫描消息，若发现未配对 `tool_result` 的 `tool_use`，则安全截断会话历史至上一个一致性状态。
- **影响文件**：
  - `src/main/java/com/bingtangcode/core/SessionManager.java`
- **依赖任务**：任务 2
- **参考资料**：
  - `ValidateMessageChain` 伪代码及大模型对 `tool_use` 与 `tool_result` 的配对规范。

---

## 任务 5：实现会话恢复时的 Token 超限就地压缩与时间跨度提醒
- **说明**：在读取历史会话合并指令和记忆索引后，如果总 Token 超限，触发一次 `ContextCompressor.compressHistory`；同时，若检测到相邻消息时间差超过 6 小时，向会话历史中插入一条带有系统时效提示的特殊 `User` 消息。
- **影响文件**：
  - `src/main/java/com/bingtangcode/core/SessionManager.java`
  - `src/main/java/com/bingtangcode/core/DialogueManager.java`（暴露就地压缩接口或直接调用）
- **依赖任务**：任务 4
- **参考资料**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java#L517-L586)（`compressHistory` 方法的调用条件与逻辑）

---

## 任务 6：实现启动时异步扫描清理 30 天过期会话
- **说明**：在 MewCode 启动时，开启异步线程扫描 `.bingtangcode/sessions/` 下的文件，解析文件名中的前 15 位时间戳，将超出 30 天未更新的会话文件执行删除。
- **影响文件**：
  - `src/main/java/com/bingtangcode/core/SessionManager.java`
- **依赖任务**：任务 3
- **参考资料**：
  - 会话 ID 命名格式与 Java 异步并发类。

---

## 任务 7：实现自动记忆收集器（异步提炼分类笔记与索引精简）
- **说明**：监听 Agent Loop 自然结束事件。在不需要工具调用时，后台异步调用大模型进行知识提取。大模型根据历史对话提炼用户偏好、纠正反馈、项目知识和参考资料，保存为 Markdown 独立文件，同时将完整的旧 `MEMORY.md` 索引传入模型，使其输出限制在 200 行内的更新后索引文件并写入。
- **影响文件**：
  - `src/main/java/com/bingtangcode/agent/AgentLoop.java`（生命周期监听）
  - `src/main/java/com/bingtangcode/core/DialogueManager.java`（异步调用接口）
  - 新增 `src/main/java/com/bingtangcode/core/AutoMemoryCollector.java`（专门的记忆提炼服务）
- **依赖任务**：任务 2
- **参考资料**：
  - [AgentLoop.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/agent/AgentLoop.java#L87-L90)
  - 自动记忆 F39 与分类管理设计。

---

## 任务 8：接入主流程
- **说明**：将多层级指令载入、会话文件管理、启动异常恢复校验、过期清理与异步记忆提炼全部整合接入 `AgentLoop` 及 `Main` 的装配阶段。项目指令和记忆索引仅在会话启动初始化时加载一次并注入 System Prompt，在会话运行期间保持静态，以最大化提示词缓存命中率。
- **影响文件**：
  - `src/main/java/com/bingtangcode/Main.java`
  - `src/main/java/com/bingtangcode/agent/AgentLoop.java`
  - `src/main/java/com/bingtangcode/core/SessionManager.java`
- **依赖任务**：任务 1 至 任务 7
- **参考资料**：
  - [Main.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/Main.java)

---

## 任务 9：端到端验证
- **说明**：编写综合测试用例，从“多级指令解析”、“JSONL 会话落盘与异常恢复截断”、“Token 超限就地压缩”、“30 天会话清理”及“大模型异步提炼记忆索引更新”等维度对双层记忆系统进行端到端全覆盖集成测试。
- **影响文件**：
  - 在 `src/test/java/com/bingtangcode/core/` 下新增 `MemorySystemEndToEndTest.java` 
- **依赖任务**：任务 8
- **参考资料**：
  - [docs/checklist.md](file:///Users/laq/Documents/bingtangcode/docs/checklist.md) 验收条件。
