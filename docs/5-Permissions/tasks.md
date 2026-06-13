# tasks.md · 五层防御权限系统

## 概述

8 个核心任务 + 2 个接续任务 + 1 个端到端验证，共 11 个任务。按依赖顺序排列。

**设计要点**（实现时遵守）：
- 权限检查集中在 `DialogueManager.executeToolBatch()` 入口，不侵入单个 Tool 类
- 黑名单写死在 PermissionGate 中，不可配置、不可绕过
- 路径沙箱从各 Tool 中抽取为共享方法，增加符号链接解析
- 规则引擎按 local > project > user 逐层匹配
- 同层 deny 优先 allow；同 effect 按书写顺序
- 拒绝时返回结构化错误，不终止 Agent Loop
- AgentLoop.Mode 与 PermissionMode 合并
- ALLOW_FOREVER 直接写入 permissions.local.yaml，下次检查自动生效

---

### 任务 1 · 定义 PermissionMode 和数据模型

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/PermissionMode.java`
- 新增 `src/main/java/com/bingtangcode/permission/PermissionRule.java`
- 新增 `src/main/java/com/bingtangcode/permission/PermissionResult.java`
- 新增 `src/main/java/com/bingtangcode/permission/ToolFriendlyName.java`

**依赖**: 无（纯数据类，不依赖已有代码）

**内容**

- `PermissionMode` enum：`DEFAULT`, `ACCEPT_EDITS`, `PLAN`, `BYPASS_PERMISSIONS`；含 `isReadOnly(Tool)` 工具过滤判断和 `getDefaultAction(boolean isReadOnly, boolean isBash)` 未命中规则时的默认行为（Allow/Ask）
- `PermissionRule` record：`String toolName`, `String pattern`, `PermissionAction action`（ALLOW/DENY），`String source`（来源标识，如 "user"/"project"/"local"）
- `PermissionResult` record：`boolean allowed`, `String reason`, `String matchedRule`（用于结构化错误信息），静态工厂方法 `allow()`、`deny(String reason, String matchedRule)`
- `ToolFriendlyName`：内部名 → 友好名映射 + 主参数提取方法（从 ToolCall.parameters 中提取 command 或 file_path；Glob/Grep 提取 directory），含 `static String friendlyName(String internalName)` 和 `static String extractMainParam(String internalName, Map<String, Object> params)`

**验证参考**: spec.md 权限模式枚举、工具友好名映射表、优先级裁决流程

---

### 任务 2 · 路径沙箱抽取与强化

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/PathSandbox.java`
- `src/main/java/com/bingtangcode/tool/tools/ReadFileTool.java`
- `src/main/java/com/bingtangcode/tool/tools/WriteFileTool.java`
- `src/main/java/com/bingtangcode/tool/tools/EditFileTool.java`
- `src/main/java/com/bingtangcode/tool/tools/FindFilesTool.java`
- `src/main/java/com/bingtangcode/tool/tools/SearchContentTool.java`

**依赖**: 无

**内容**

- `PathSandbox` 提供静态方法 `PathSandbox.validate(Path projectRoot, String filePath)`：
  1. `projectRoot.resolve(filePath)` 得到初步路径
  2. `path.toRealPath()` 解析所有符号链接拿到真实路径
  3. `realPath.startsWith(projectRoot.toRealPath())` 前缀判断
  4. 不通过则抛出 `PathViolationException`（checked 或 unchecked），由 PermissionGate 捕获转 PermissionResult.deny()
- 五个文件工具类的路径校验改为调用 `PathSandbox.validate(projectRoot, filePath)`，删除各工具中重复的 resolve+startsWith 代码
- 注：ExecuteCommandTool 不需要路径沙箱（它的参数是 command 不是 path），不需要修改

**参考**:
- `ReadFileTool.java:58` 当前 resolve + startsWith 模式
- `WriteFileTool.java:45` 当前 resolve + startsWith 模式
- `EditFileTool.java:55` 当前 resolve + startsWith 模式
- `FindFilesTool.java:50` 当前 resolve + normalize + startsWith 模式
- `SearchContentTool.java:55` 当前 resolve + normalize + startsWith 模式

---

### 任务 3 · 黑名单引擎

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/Blacklist.java`

**依赖**: 任务 1（PermissionResult）

**内容**

- `Blacklist` 类：内部维护一个 `List<Pattern>` 在静态初始化块中编译 spec.md 中列出的全部高危正则，编译一次后缓存
- 提供 `PermissionResult check(String command)` 方法：
  - 遍历所有 Pattern，任一匹配即返回 `PermissionResult.deny("匹配黑名单: <具体pattern>", "blacklist")`
  - 无一匹配返回 `PermissionResult.allow()`
- 仅对 Bash（execute_command）调用，对文件工具不调用
- 黑名单正则清单硬编码在类中，不读任何配置文件

**参考**: spec.md 黑名单正则清单（18 条）

---

### 任务 4 · 三层配置文件加载器

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/PermissionConfigLoader.java`

**依赖**: 任务 1（PermissionRule）

**内容**

- `PermissionConfigLoader` 类，构造函数接收 `Path projectRoot`
- 提供加载方法，按优先级返回合并后的规则列表：
  1. `loadUserRules()` — 读取 `~/.bingtangcode/permissions.yaml`
  2. `loadProjectRules()` — 读取 `<projectRoot>/.bingtangcode/permissions.yaml`
  3. `loadLocalRules()` — 读取 `<projectRoot>/.bingtangcode/permissions.local.yaml`
- YAML 解析：用 Jackson YAML 读取，结构为 `defaultMode`（可选字符串）+ `rules`（列表，每项含 `tool`/`pattern`/`action`）
- 文件不存在时返回空列表（不报错）。解析失败时 System.err 打印告警 + 返回空列表。
- `loadDefaultMode()` — 按 local > project > user 优先级取第一个存在的 `defaultMode` 字段，都不存在返回 `DEFAULT`
- 提供 `getMergedRules()` 返回合并后的有序列表：`userRules + projectRules + localRules`（优先级倒序，高优先者放后面方便逐层覆盖，实际裁决在 RuleEngine 中倒序遍历）

**参考**: `ConfigManager.java:30-55` YAML 读取模式、`pom.xml` 中已有的 `jackson-dataformat-yaml` 依赖

---

### 任务 5 · 规则引擎

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/RuleEngine.java`

**依赖**: 任务 1（PermissionRule、ToolFriendlyName）、任务 4（PermissionConfigLoader）

**内容**

- `RuleEngine` 类：
  - 构造函数接收 `PermissionConfigLoader configLoader`
  - `check(String internalToolName, Map<String, Object> params, Path projectRoot)` 方法：
    1. 用 `ToolFriendlyName` 获取友好名和主参数值
    2. 对文件类工具，将主参数（file_path 或 directory）转为项目相对路径
    3. 按优先级依次匹配：localRules → projectRules → userRules
    4. 每层内部匹配逻辑：
       - 遍历所有规则：`rule.toolName` 与工具友好名完全匹配
       - `rule.pattern` 与主参数值进行精确或 glob 匹配（含 `*`/`**` 则为 glob）
       - 收集所有命中的规则
       - 如果其中有 deny → 立即返回 deny（deny 优先）
       - 否则取第一个命中的 allow → 返回 allow
    5. 所有层均无命中 → 返回 null 表示"未决定"（由上层 PermissionGate 按模式兜底）
  - glob 匹配实现：使用 `java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern)`

**参考**: spec.md 优先级裁决流程、规则匹配语义

---

### 任务 6 · PermissionGate 集中权限门

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/PermissionGate.java`

**依赖**: 任务 2（PathSandbox）、任务 3（Blacklist）、任务 5（RuleEngine）

**内容**

- `PermissionGate` 类——五层防御的编排者：
  - 构造函数接收 `RuleEngine ruleEngine`, `PathSandbox sandbox`, `Blacklist blacklist`, `PermissionModeProvider modeProvider`, `Path projectRoot`, `HumanInTheLoopHandler hitlHandler`
  - `check(ToolCall tc)` 方法——按五层顺序执行：
    1. **黑名单**：仅对 `execute_command` 调用 `blacklist.check(command)`，命中则返回 DENY
    2. **路径沙箱**：对文件类工具（Read/Write/Edit/Glob/Grep）调用 `PathSandbox.validate()`，越界则返回 DENY
    3. **规则引擎**：调用 `ruleEngine.check(toolName, params, projectRoot)`，如果有明确 allow/deny 则返回
    4. **模式兜底**：未命中规则时，按当前 `PermissionMode` 的 `getDefaultAction()` 决定：
       - Allow → 直接返回 ALLOW
       - Ask → 调用 `hitlHandler.ask(tc, mode)` 进入人在回路
    5. **人在回路**：阻塞等待用户决定，根据选择执行：ALLOW_ONCE → 允许、DENY_ONCE → 拒绝、ALLOW_FOREVER → 写规则到 permissions.local.yaml + 允许
  - `setMode(PermissionMode mode)` / `getMode()` 方法
  - `addSessionRule(PermissionRule rule)` 委托给 RuleEngine

**接口定义**:
- `HumanInTheLoopHandler` 接口：`AskResult ask(ToolCall tc, PermissionMode mode)` 返回用户选择
- `PermissionModeProvider` 接口：`PermissionMode getCurrentMode()` 供 Gate 动态获取当前模式
- `AskResult` enum：`ALLOW_ONCE`, `ALLOW_FOREVER`, `DENY_ONCE`

**参考**: spec.md 设计骨架五层防御流程图、优先级裁决流程

---

### 任务 7 · 人在回路终端 UI

**影响文件**
- 新增 `src/main/java/com/bingtangcode/permission/PermissionPrompt.java`
- 修改 `src/main/java/com/bingtangcode/tui/TerminalIO.java`（可能需要增加读取单键输入的方法）

**依赖**: 任务 6（HumanInTheLoopHandler 接口）、已有的 TerminalIO

**内容**

- `PermissionPrompt` 实现 `HumanInTheLoopHandler` 接口：
  - 构造函数接收 `TerminalIO terminalIO`
  - `ask(ToolCall tc, PermissionMode mode)` 方法：
    1. 暂停当前 token 流的输出（通过 EventBus 或直接锁——在 caller 是 executeToolBatch 的串行路径上，天然阻塞）
    2. 在终端底部打印多行选项块（见 spec.md 人在回路交互图），包含工具名、主参数、未命中规则提示
    3. 使用光标移动 + 回车 / 数字键直选 / 快捷键三种输入方式
    4. 监听按键：
       - `↑`/`k` → 光标上移
       - `↓`/`j` → 光标下移
       - `Enter` → 确认当前高亮项
       - `1`/`y` → ALLOW_ONCE
       - `2` → ALLOW_FOREVER
       - `3`/`n`/`d` → DENY_ONCE
    5. 返回对应的 `AskResult`
  - 无超时，循环等待有效按键输入
  - 选择完成后清除选项块、恢复光标位置

**实现要点**:
- JLine3 已支持原始模式（raw mode）下按字节读取按键，需处理 ANSI 转义序列（`\033[A` = 上箭头, `\033[B` = 下箭头）
- 高亮效果通过 ANSI 反转色实现
- TerminalIO 可能需要新增 `readKey()` 方法或暴露其 LineReader 实例

**参考**: `TerminalIO.java:55-65` confirmCommand 方法、JLine3 `LineReader.readCharacter()` API

---

### 任务 8 · PermissionModeProvider 配置集成与 ALLOW_FOREVER 持久化

**影响文件**
- `src/main/java/com/bingtangcode/config/ConfigManager.java`（可能需要增加 `permissions` 配置段读取）
- 新增 `src/main/java/com/bingtangcode/permission/PermissionConfigManager.java`（或合并到 ConfigManager）
- `src/main/java/com/bingtangcode/permission/PermissionGate.java`（ALLOW_FOREVER 写入逻辑）

**依赖**: 任务 4（PermissionConfigLoader）、任务 6（PermissionGate）

**内容**

- `PermissionConfigManager` 类（或 ConfigManager 扩展）：
  - 提供 `PermissionMode loadDefaultMode(Path projectRoot)` — 委托给 PermissionConfigLoader
  - 提供 `void appendRuleToLocal(Path projectRoot, PermissionRule rule)` — 将一条 allow 规则追加写入 `<projectRoot>/.bingtangcode/permissions.local.yaml`：
    - 文件不存在则创建含 `rules:` 的空骨架
    - 追加新条目到 rules 列表末尾
    - 注意保持 YAML 缩进格式
  - ALLOW_FOREVER 时由 PermissionGate 调用此方法持久化

**参考**: spec.md 三层规则文件、ConfigManager.java 现有结构

---

### 任务 9 · 接入 DialogueManager.executeToolBatch

**影响文件**
- `src/main/java/com/bingtangcode/core/DialogueManager.java`
- `src/main/java/com/bingtangcode/core/RoundResult.java`（可能需要扩展）

**依赖**: 任务 6（PermissionGate）、任务 7（HumanInTheLoopHandler）

**内容**

- `DialogueManager` 构造函数增加 `PermissionGate` 参数
- 在 `executeOne()` 方法中（或 `executeToolBatch()` 遍历前），对每个 ToolCall 调用 `permissionGate.check(tc)`：
  - 返回 ALLOW → 正常执行 `toolExecutor.execute()`
  - 返回 DENY → 构造结构化错误 ToolResult：
    ```
    "权限拒绝: <FriendlyName>(<mainParam>) 被 <reason> 拦截\n  匹配规则: <matchedRule>\n  建议: 修改参数或请求调整规则"
    ```
    设置 `isError=true`，不执行工具，但继续处理后续 tool call
- 拒绝后 Agent Loop 正常继续下一轮迭代（模型收到错误 ToolResult 后自主调整策略）
- 明确：权限拦截不设置 `hasUnknown=true`、不增加 `unknownToolStreak`

**参考**:
- `DialogueManager.java:203` executeOne 方法
- `DialogueManager.java:167` executeToolBatch 方法
- spec.md 被拒错误信息格式

---

### 任务 10 · AgentLoop Mode 合并与 Shift+Tab 模式切换

**影响文件**
- `src/main/java/com/bingtangcode/agent/AgentLoop.java`
- `src/main/java/com/bingtangcode/core/SessionManager.java`
- `src/main/java/com/bingtangcode/core/SystemReminderManager.java`

**依赖**: 任务 1（PermissionMode）、任务 9（DialogueManager 已接入 PermissionGate）

**内容**

- 删除 `AgentLoop.Mode` 枚举，统一使用 `PermissionMode`
- `AgentLoop` 构造函数接收初始 `PermissionMode`
- `selectTools()` 方法：PLAN 模式只返回 `isReadOnly()` 为 true 的工具（通过 `PermissionMode.isPlanMode()` 判断）
- `SystemReminderManager.onModeSwitch()` 参数类型改为 `PermissionMode`
- `SessionManager` 中绑定 Shift+Tab 按键：
  - 利用 JLine3 的 `KeyMap` 绑定 `"\t"` 仅在 shift 按下时触发
  - 循环切换：`DEFAULT → ACCEPT_EDITS → PLAN → BYPASS → DEFAULT`
  - 切换后调用 `agentLoop.setMode(newMode)` + 通知 `permissionGate.setMode(newMode)` + 打印提示行
- `/plan` 命令 → `setMode(PLAN)`, `/do` 命令 → `setMode(DEFAULT)`
- 移除旧的 `AgentLoop.Mode.PLAN` / `AgentLoop.Mode.FULL` 所有引用

**参考**: `AgentLoop.java:19` Mode 枚举、`AgentLoop.java:102` setMode、`SessionManager.java:53` 命令解析、`TerminalIO.java` JLine3 按键处理

---

### 任务 11 · 主流程装配与端到端验证

**影响文件**
- `src/main/java/com/bingtangcode/Main.java`

**依赖**: 任务 1~任务 10

**内容**

- Main.java 中按依赖顺序装配：
  1. `ConfigManager config = new ConfigManager()`
  2. `PermissionConfigLoader configLoader = new PermissionConfigLoader(projectRoot)`
  3. `PermissionMode initialMode = configLoader.loadDefaultMode()`
  4. `RuleEngine ruleEngine = new RuleEngine(configLoader)`
  5. `Blacklist blacklist = new Blacklist()`
  6. `PathSandbox sandbox = new PathSandbox()`
  7. `PermissionGate gate = new PermissionGate(ruleEngine, sandbox, blacklist, () -> agentLoop.getMode(), projectRoot, permissionPrompt)`
  8. `PermissionPrompt prompt = new PermissionPrompt(terminalIO)`
  9. `DialogueManager` 构造函数注入 `PermissionGate`
  10. `AgentLoop` 构造函数接收初始 `PermissionMode`
  11. `SessionManager` 绑定 Shift+Tab + /plan + /do
  12. `eventBus.subscribe(tuiEventListener)` 正常注册
- `mvn clean compile` 通过
- 端到端场景验证（见 checklist.md 端到端验收）

**端到端验证场景**:
1. 黑名单拦截：请求执行 `sudo rm -rf /` 被拒绝，返回结构化错误，Agent 不终止
2. 路径沙箱：请求读 `/etc/passwd` 被拒绝
3. 规则引擎 allow：配置 `Bash(git *)` → allow，执行 `git status` 直接通过
4. 规则引擎 deny：配置 `Write(*.java)` → deny，写文件被拒
5. default 模式 Ask：执行未在规则中的命令，弹确权块
6. 人在回路 allow once：选 1 后本次放行
7. 人在回路 allow forever：选 2 后写入 permissions.local.yaml，后续自动放行
8. 人在回路 deny once：选 3 后拒绝，模型收到错误
9. bypassPermissions 模式：全 Allow，不走 Ask，但黑名单仍拦截
10. plan 模式：只暴露只读工具，写/执行不可用
11. Shift+Tab 循环切换模式
12. /plan 和 /do 切换
