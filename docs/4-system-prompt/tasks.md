# tasks.md · 系统提示工程

## 概述

5 个任务，按依赖顺序排列。每个任务标注影响文件、依赖和参考定位。

**设计要点**（实现时遵守）：
- SystemPromptBuilder 只负责组装 7 个固定模块 + 环境信息，不处理动态内容（模式切换、自定义指令、Skill 等）
- SystemReminderManager 只负责决定"本轮注入什么"，不操作对话历史
- 动态内容通过 `<system-reminder>` 标签包裹的 USER 消息注入，AgentLoop 在每轮 doRound 前调用 reminderManager 获取并写入历史
- 七个固定模块内容已定稿，直接写入 SystemPromptBuilder 各 build* 方法

---

### 任务 1 · SystemPromptBuilder — 组装七模块系统提示

**影响文件**
- 新增 `src/main/java/com/bingtangcode/core/SystemPromptBuilder.java`

**依赖**: 无

**内容**
- 新建 `SystemPromptBuilder` 类，提供 `String build(String providerName, String modelName, EnvInfo env)` 方法
- 七个模块独立 private 方法：`buildIdentity()`、`buildConstraints()`、`buildTaskMode()`、`buildActionExecution()`、`buildToolUsage()`、`buildTone()`、`buildTextOutput()`
- `buildEnvSection(EnvInfo env)` 组装环境信息（工作目录、平台、Shell、OS、日期、git 状态）
- 模块间用空行分隔，整体按优先级从前往后拼接
- `EnvInfo` 为简单 record：`String workDir, String platform, String shell, String osVersion, String date, String gitStatus`
- Main.java 中原 `buildSystemPrompt()` 方法替换为调用 `new SystemPromptBuilder().build(...)`

**参考**:
- 七模块定稿内容见 `docs/4-system-prompt/spec.md` 设计骨架
- `Main.java:23` 当前 `buildSystemPrompt()` 方法
- `DialogueManager.java:32` 构造函数接收 systemPrompt 参数

---

### 任务 2 · SystemReminderManager — 规划模式提醒频率控制

**影响文件**
- 新增 `src/main/java/com/bingtangcode/core/SystemReminderManager.java`

**依赖**: 无

**内容**
- 新建 `SystemReminderManager` 类
- 核心方法：
  - `void onModeSwitch(AgentLoop.Mode mode)` — 标记模式刚切换，重置轮次计数器；切到 FULL 模式时清空状态
  - `String getReminder()` — PLAN 模式下返回本轮提醒内容，FULL 模式返回 null
  - `void onRoundComplete()` — 轮次计数 +1
- 频率规则（N=3）：
  - 模式刚切换到 PLAN → FULL
  - `roundCount % 3 == 0` → FULL
  - 其他 → SHORT
- 提醒文本常量：
  - FULL: `Plan模式：仅可用只读工具，专注于分析问题、制定计划，不要尝试修改文件或执行命令。`
  - SHORT: `Plan模式：仅可用只读工具`
- 所有提醒内容外包裹 `<system-reminder>...</system-reminder>` 标签

**参考**:
- `AgentLoop.java:16` Mode 枚举
- spec.md SystemReminderManager 接口设计

---

### 任务 3 · AgentLoop 集成 SystemReminderManager

**影响文件**
- `src/main/java/com/bingtangcode/agent/AgentLoop.java`

**依赖**: 任务 2（SystemReminderManager 已实现）

**内容**
- AgentLoop 构造函数新增 `SystemReminderManager` 参数并存储
- `setMode()` 方法中调用 `reminderManager.onModeSwitch(mode)`
- `run()` 方法中，在 `dialogue.addUserMessage(userInput)` 之后、while 循环之前，调用 `injectReminder()` 注入首轮提醒
- while 循环内，每轮 doRound 之前调用 `injectReminder()`
- `injectReminder()` private 方法：
  1. 调用 `reminderManager.getReminder()`
  2. 非 null 时调用 `dialogue.addMessage(new Message(Role.USER, reminderText))`
  3. 提醒消息会被 `buildApiMessages()` 与下一条 USER 消息合并
- 每轮结束后调用 `reminderManager.onRoundComplete()`

**参考**:
- `AgentLoop.java:37` run() 方法入口
- `AgentLoop.java:44` while 循环体
- `AgentLoop.java:86-88` setMode() 方法
- `AgentLoop.java:102-118` runRoundWithRetry() 重试逻辑
- `DialogueManager.java:34` history 列表（addMessage 方式）

---

### 任务 4 · 接入主流程 Main.java

**影响文件**
- `src/main/java/com/bingtangcode/Main.java`
- `src/main/java/com/bingtangcode/tool/tools/EditFileTool.java`（可选：工具描述强化）
- `src/main/java/com/bingtangcode/tool/tools/WriteFileTool.java`（可选：工具描述强化）

**依赖**: 任务 1~3

**内容**
- Main.java 中：
  1. 创建 `SystemPromptBuilder` 实例
  2. 构建 `EnvInfo`（从 `System.getProperty` 获取平台/Shell/OS，从 `Paths.get("")` 获取工作目录，日期用 `java.time.LocalDate.now()`，git 状态用 `git status` 命令获取快照，失败则填 "未知"）
  3. 调用 `systemPromptBuilder.build(providerName, modelName, envInfo)` 替换原 `buildSystemPrompt(providerName, modelName)`
  4. 创建 `SystemReminderManager` 实例
  5. AgentLoop 构造函数注入 `SystemReminderManager`
  6. `mvn clean compile` 通过
- 工具描述强化（双重规则）：
  - EditFileTool.getDescription(): 在现有描述末尾追加 "注意：调用本工具前必须先使用 read_file 读取目标文件，否则调用将失败。"
  - WriteFileTool.getDescription(): 在现有描述末尾追加 "注意：对于已有文件的修改，优先使用 edit_file 而非 write_file，以减少不必要的内容传输。"
  - 其他工具描述根据 spec.md 关键规则审查，是否需要调整

**参考**:
- `Main.java:23-31` 当前 buildSystemPrompt() 方法
- `Main.java:89-90` DialogueManager 构造
- `Main.java:97-99` AgentLoop 构造
- `Main.java:103` SessionManager 构造
- `SessionManager.java:44-53` /plan /do 命令处理（含 mode switch，需确认 setMode 触发 onModeSwitch）

---

### 任务 5 · 端到端验证

**影响文件**: 无（手工测试）

**依赖**: 任务 4

**内容**
启动 bingtangCode，验证以下场景：

1. **系统提示生效** — 输入"你是谁"，模型回复包含 bingtangCode 身份信息
2. **工具规则生效** — 输入"帮我读一下 pom.xml"，模型直接调 read_file 而非用 Bash cat；模型编辑文件前先 Read
3. **Plan Mode 注入** — 输入 `/plan`，终端显示切换提示；再输入"帮我修改 pom.xml"，模型应拒绝或只读分析
4. **Plan Mode 短标签** — 在 Plan 模式下连续输入 4 轮任务，观察第 2、3 轮注入的是短标签，第 4 轮（N=3）注入完整版
5. **Do Mode 切回** — 输入 `/do`，终端显示切换提示，全工具恢复可用
6. **环境信息可见** — 启动会话后在对话中问"当前工作目录是什么"，模型应能回答（从系统提示的环境信息段获取）
