# 上下文管理（Context Management）开发任务表

## 任务列表

### 1. 支持 Context Window 与 SessionID 初始化
- **影响文件**：
  - [ConfigManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/config/ConfigManager.java)
  - [Main.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/Main.java)
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **依赖任务**：无
- **参考资料定位**：
  - `ConfigManager.java`: 参考类中的属性加载逻辑，在 `config.yaml` 映射中增加 `context_window` 配置读取。
  - `Main.java#L191`: 实例化 `DialogueManager` 的入口。
- **任务描述**：
  - 在 `ConfigManager` 中解析 `context_window`（默认 128,000）。
  - 在应用启动时（`Main`）生成唯一的会话标识 `sessionId`（格式：`yyyyMMddHHmmss-UUID`），并在实例化 `DialogueManager` 时传入。

---

### 2. 实现轻量 Token 估算器
- **影响文件**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **依赖任务**：任务 1
- **参考资料定位**：
  - `DialogueManager.java#L66-105`: 参考在 `StreamCallback.onUsage` 中更新 token 的逻辑。
- **任务描述**：
  - 在 `DialogueManager` 中记录上一次 API 响应返回的真实 `lastApiInputTokens`、`lastApiOutputTokens` 及发送该请求时的 `lastApiHistorySize`。
  - 编写 `estimateCurrentTokens()` 方法。如果 `lastApiInputTokens <= 0` 则执行全量字符估算；若已锚定，基准 Token 设为 `lastApiInputTokens + lastApiOutputTokens`，增量字符累加时跳过索引为 `lastApiHistorySize` 的回复本身（从 `lastApiHistorySize + 1` 开始），除以常数 `3.5` 作为增量估算值与基准相加。

---

### ### 3. 轻量预防：大工具结果拦截落盘
- **影响文件**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **依赖任务**：任务 2
- **参考资料定位**：
  - `DialogueManager.java#L189-223`: `executeToolBatch` 工具执行完毕并收集 `ToolResult` 的逻辑。
- **任务描述**：
  - 拦截新执行的单个 `ToolResult`。如果其 content 在 UTF-8 编码下的字节大小超过 50,000 字节，则将完整内容保存至项目下的 `.bingtangcode/sessions/<sessionId>/tool-results/<toolCallId>` 中。
  - 将内存中的 `ToolResult` 内容替换为预览格式，包括：原始字节数、头部前 20 行或前 2048 字节（取较短者）预览、存盘文件的绝对路径、以及要求模型使用 ReadFile 重新读取的提示。

---

### 4. 轻量预防：单条消息合计超限裁剪
- **影响文件**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **依赖任务**：任务 3
- **任务描述**：
  - 检查单条消息内所有工具结果（包括未落盘和已落盘的）的总字节大小。
  - 如果总大小超过 200,000 字节，将尚未落盘的工具结果按字节大小由大到小排序，依次对其实施落盘和预览替换，直到该条消息中所有剩余未落盘结果的合计字节数不超过 200,000 字节。

---

### 5. 重量兜底：LLM 摘要生成与草稿剥离
- **影响文件**：
  - 新建 `ContextCompressor.java` 在 `com.bingtangcode.core` 包下。
- **依赖任务**：任务 2
- **任务描述**：
  - 创建 `ContextCompressor` 模块，它构造一个不带任何 Tools、包含特定 System Prompt 的临时对话请求发送给 `LLMProvider`。
  - Prompt 明确禁止模型调用任何工具，并强制模型先在 `<draft>...</draft>` 标签中撰写分析草稿，后接包含九个固定二级标题的正式摘要。
  - 获取生成的完整字符串后，利用正则表达式或字符串裁剪剥离掉 `<draft>...</draft>` 标签及其包含的全部草稿内容。

---

### 6. 重量兜底：历史消息替换与边界消息拼接
- **影响文件**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **依赖任务**：任务 5
- **任务描述**：
  - 实现历史裁剪逻辑：从 history 尾部（最新消息）往前遍历，累加估算 token，当“累计 token >= 10000 并且 累计保留消息条数 >= 5”时，该位置及之后的消息作为近期原文保留。
  - 将该边界之前的所有消息（除 System Prompt 以外）提交给 `ContextCompressor` 生成摘要。
  - 删除被摘要的历史消息，在 System Prompt 之后插入一条 `USER` 消息，其内容为：`生成的结构化摘要` + `边界消息（重要提示使用 ReadFile 重新读取原文）`。

---

### 7. 接入主流程：自动重量兜底触发与熔断
- **影响文件**：
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **依赖任务**：任务 6
- **参考资料定位**：
  - `DialogueManager.java#L66-68`: API 请求前构建消息的阶段。
- **任务描述**：
  - 在 `DialogueManager.doRound` 构建发送消息前，估算当前上下文 token。
  - 判断是否触发自动重量压缩：若当前估算 Token $\ge \text{context\_window} - 20,000 - 13,000$。
  - 对摘要生成的 LLM 请求进行异常捕获，若连续失败 3 次则触发熔断，设置 `autoTriggerEnabled = false`，停止后续的自动压缩，但在终端打印警告并让主对话循环继续运行。

---

### 8. 手动触发指令 `/compact` 接入
- **影响文件**：
  - [SessionManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SessionManager.java)
  - [DialogueManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/DialogueManager.java)
- **参考资料定位**：
  - `SessionManager.java#L59-74`: 处理用户输入指令 `/plan`, `/do` 等的路由逻辑。
- **任务描述**：
  - 在 `SessionManager` 循环中拦截 `/compact` 指令。
  - 手动触发时有效阈值为：当前估算 Token $\ge \text{context\_window} - 20,000 - 3,000$（或者无条件强制压缩）。
  - 若执行了压缩，成功后打印压缩前后的 token 变化提示给用户。

---

### 9. 端到端验证与异常情况测试
- **影响文件**：
  - 终端运行测试及新建测试用例（若有）
- **任务描述**：
  - 模拟大工具结果，观察是否自动在 `.bingtangcode/sessions/` 写入落盘文件，并且终端消息中仅含预览和指示模型 ReadFile 的提示。
  - 构造多轮会话或大幅增加测试数据，确认其能正常自动触发重量兜底，并检查草稿是否被剥离，九大标题是否正确，以及是否追加了边界提示消息。
  - 测试连续摘要失败 3 次下的熔断机制，验证主对话是否能不受影响继续运行。
