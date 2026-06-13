# checklist.md · 五层防御权限系统

## 数据模型

- [ ] `PermissionMode` 枚举含 `DEFAULT`, `ACCEPT_EDITS`, `PLAN`, `BYPASS_PERMISSIONS` 四个值（`grep "enum PermissionMode" src/main/java/com/bingtangcode/permission/PermissionMode.java` 返回结果）
- [ ] `PermissionRule` record 含 `toolName`, `pattern`, `action`, `source` 四个字段（`grep "record PermissionRule"` 返回结果）
- [ ] `PermissionResult` record 含 `allowed`, `reason`, `matchedRule`（`grep "record PermissionResult"` 返回结果）
- [ ] `ToolFriendlyName.friendlyName("execute_command")` 返回 `"Bash"`（源码审查或单元测试）
- [ ] `ToolFriendlyName.friendlyName("read_file")` 返回 `"Read"`（源码审查或单元测试）
- [ ] `ToolFriendlyName.friendlyName("write_file")` 返回 `"Write"`（源码审查或单元测试）
- [ ] `ToolFriendlyName.friendlyName("edit_file")` 返回 `"Edit"`（源码审查或单元测试）
- [ ] `ToolFriendlyName.friendlyName("find_files")` 返回 `"Glob"`（源码审查或单元测试）
- [ ] `ToolFriendlyName.friendlyName("search_content")` 返回 `"Grep"`（源码审查或单元测试）

## 工具友好名 → 主参数提取

- [ ] Read/Write/Edit 工具提取主参数 `file_path`（`extractMainParam("read_file", params)` 返回 params 中的 file_path 值）
- [ ] Bash 工具提取主参数 `command`（`extractMainParam("execute_command", params)` 返回 params 中的 command 值）
- [ ] Glob/Grep 工具提取主参数 `directory`（`extractMainParam("find_files", params)` 返回 params 中的 directory 值）

## 路径沙箱

- [ ] `PathSandbox` 类存在（`grep "class PathSandbox" src/main/java/com/bingtangcode/permission/PathSandbox.java` 返回结果）
- [ ] `PathSandbox.validate()` 内部调用 `Path.toRealPath()` 解析符号链接（源码审查）
- [ ] 传入 `../../etc/passwd`，`toRealPath()` 解析后不以 projectRoot 开头 → 拒绝（源码逻辑 + 端到端测试）
- [ ] 在项目内创建指向 `/etc/passwd` 的符号链接，读此链接 → 被拒绝（端到端测试）
- [ ] ReadFileTool、WriteFileTool、EditFileTool、FindFilesTool、SearchContentTool 五个工具均调用 `PathSandbox.validate()`（`grep "PathSandbox.validate"` 在每个工具文件中返回 ≥1 条）
- [ ] 各工具类中删除原有的 resolve+startsWith 路径校验代码（`grep "startsWith(projectRoot)"` 在五个工具中均返回 0 条）

## 黑名单

- [ ] `Blacklist` 类存在（`grep "class Blacklist" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回结果）
- [ ] 黑名单 Pattern 在静态初始化块编译一次（源码审查：`static { ... }` 块包含 `Pattern.compile` 调用）
- [ ] `grep "rm.*rf" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 `rm -rf /` 拦截）
- [ ] `grep "sudo" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 `sudo` 拦截）
- [ ] `grep "curl.*bash" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 curl|bash 拦截）
- [ ] `grep "chmod.*777" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 chmod 777 拦截）
- [ ] `grep "mkfs" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 mkfs 拦截）
- [ ] `grep "shutdown\|reboot" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含关机重启拦截）
- [ ] `grep "fork\|fork.*bomb\|:\s*(" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 fork bomb 拦截）
- [ ] `grep "systemctl" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 systemctl stop/disable 拦截）
- [ ] `grep "git.*push.*force.*main\|git.*push.*force.*master" src/main/java/com/bingtangcode/permission/Blacklist.java` 返回 ≥1 条（含 force push main/master 拦截）
- [ ] 黑名单 Pattern 总数 ≥ 15（源码计数）
- [ ] 输入 `rm -rf /` → 被拒绝，错误信息含 "黑名单"（端到端测试）
- [ ] 输入 `sudo cat /etc/passwd` → 被拒绝，错误信息含 "黑名单"（端到端测试）
- [ ] 在 `BYPASS_PERMISSIONS` 模式下 `rm -rf /` 仍被拦截（端到端测试）

## 规则 YAML 加载

- [ ] `PermissionConfigLoader` 类存在（`grep "class PermissionConfigLoader" src/main/java/com/bingtangcode/permission/PermissionConfigLoader.java` 返回结果）
- [ ] 读取 `~/.bingtangcode/permissions.yaml`（源码审查：路径包含 `user.home` + `.bingtangcode/permissions.yaml`）
- [ ] 读取 `<project>/.bingtangcode/permissions.yaml`（源码审查：路径用 projectRoot 拼接）
- [ ] 读取 `<project>/.bingtangcode/permissions.local.yaml`（源码审查：路径用 projectRoot 拼接）
- [ ] 文件不存在时返回空规则列表，不崩溃（`grep "File|Path|exists"` 在 PermissionConfigLoader 中有判断或 catch IOException）
- [ ] YAML 解析失败时 System.err 打印告警 + 返回空列表（源码审查：catch 块含 `System.err.println`）
- [ ] `getMergedRules()` 返回 user + project + local 合并后的有序列表（源码审查）
- [ ] `loadDefaultMode()` local > project > user 逐层取第一个存在的 defaultMode 字段（源码审查）
- [ ] 三层均无 defaultMode → 返回 `PermissionMode.DEFAULT`（源码审查）

## 规则引擎

- [ ] `RuleEngine` 类存在（`grep "class RuleEngine" src/main/java/com/bingtangcode/permission/RuleEngine.java` 返回结果）
- [ ] 匹配优先级：local > project > user（源码审查：check() 方法中的遍历顺序）
- [ ] 同层规则 deny 优先于 allow（源码审查：命中两条规则时 deny 获胜的短路逻辑）
- [ ] 同 effect 按书写顺序优先匹配（源码审查：遍历中第一个命中的 allow 即返回）
- [ ] 配置 `Bash(git *)` → allow，执行 `git status` → 规则引擎返回 ALLOW（端到端测试）
- [ ] 配置 `Bash(git push *)` → deny 和 `Bash(git *)` → allow 同层并存，执行 `git push origin main` → deny 获胜（端到端测试）
- [ ] 所有层均无命中 → 返回 null（未决定）
- [ ] glob 匹配：`Read(*.java)` 匹配 `src/Main.java`（端到端测试）
- [ ] glob 匹配：`Read(*.java)` 不匹配 `src/Main.kt`（端到端测试）
- [ ] glob 匹配：`Read(src/**/*.java)` 匹配 `src/com/example/Main.java`（端到端测试，`**` 递归匹配）
- [ ] 精确匹配：`Read(.env)` 仅匹配文件名为 `.env`，不匹配 `.env.backup`（端到端测试）
- [ ] 路径匹配统一使用项目相对路径（源码审查：将绝对路径转为相对路径后匹配）

## 权限模式

- [ ] DEFAULT 模式：只读工具走 Allow，Bash 走 Ask（走不到规则引擎 → 模式兜底）
- [ ] ACCEPT_EDITS 模式：Read/Write/Edit/Glob/Grep 走 Allow，Bash 走 Ask
- [ ] PLAN 模式：`selectTools()` 只返回 isReadOnly==true 的工具（`Bash`、`Write`、`Edit` 不在返回列表中）
- [ ] BYPASS_PERMISSIONS 模式：全部 Allow，不走 Ask（但规则引擎正常执行 deny 规则，黑名单和沙箱仍拦截）
- [ ] defaultMode 配置为 `acceptEdits` 时启动即生效（端到端测试）

## 人在回路 UI

- [ ] `PermissionPrompt` 实现 `HumanInTheLoopHandler` 接口（源码审查）
- [ ] 弹窗包含三行选项：`允许本次`、`永久允许`、`拒绝本次`（端到端观察）
- [ ] 默认高亮在第一项「允许本次」（端到端观察：首次弹窗光标在第一行）
- [ ] `↑`/`k` 上移光标，`↓`/`j` 下移光标（端到端测试）
- [ ] `Enter` 确认当前高亮项（端到端测试）
- [ ] `1`/`y` → ALLOW_ONCE（端到端测试：按键 1 立即返回不等待回车）
- [ ] `2` → ALLOW_FOREVER（端到端测试：按键 2 立即返回，后续同操作不再弹窗）
- [ ] `3`/`n`/`d` → DENY_ONCE（端到端测试：按键 n 返回拒绝）
- [ ] 无超时，无限等待用户输入（源码审查：无 timeout/timer 逻辑）
- [ ] 选择后选项块被清除，终端恢复（端到端观察：选项消失、光标恢复）

## ALLOW_FOREVER 持久化

- [ ] 选 ALLOW_FOREVER 后，`<project>/.bingtangcode/permissions.local.yaml` 中追加一条 allow 规则（端到端测试：cat 该文件验证）
- [ ] 文件不存在时自动创建含 `rules:` 的骨架（端到端测试：删除 local 文件后测试 ALLOW_FOREVER）
- [ ] 后续相同操作不再弹窗，直接放行（端到端测试：第二次同操作静默通过）

## 权限检查集中拦截

- [ ] 权限检查在 `DialogueManager.executeOne()` 或 `executeToolBatch()` 中调用，不在单个 Tool.execute() 内部（源码审查：PermissionGate.check() 调用点在 DialogueManager，不在 Tool 实现类）
- [ ] `grep "PermissionGate\|permissionGate" src/main/java/com/bingtangcode/tool/tools/ExecuteCommandTool.java` 返回 0 条
- [ ] `grep "PermissionGate\|permissionGate" src/main/java/com/bingtangcode/tool/tools/ReadFileTool.java` 返回 0 条
- [ ] `grep "PermissionGate\|permissionGate" src/main/java/com/bingtangcode/tool/tools/WriteFileTool.java` 返回 0 条

## 拒绝不终止循环

- [ ] 权限被拒后 ToolResult.isError() = true，内容含 "权限拒绝"（源码审查或端到端）
- [ ] 拒绝的 ToolResult 含被拒原因和匹配的规则来源（端到端观察：错误信息包含规则描述，如 `Bash(git push --force)`）
- [ ] 拒绝后 Agent Loop 不终止，模型可后续调整策略（端到端测试：构造拒绝场景，确认模型收到错误后继续输出文本/尝试其他工具）
- [ ] 权限拒绝不增加 `unknownToolStreak`（源码审查：DialogueManager 中 PermissionGate 拒绝的路径不与 hasUnknown 混用）
- [ ] `AgentFinished` 不会因权限拒绝触发 UNKNOWN_TOOL_LOOP 或 CANCELLED（端到端测试：连续 3 次权限拒绝，Agent 仍正常运行）

## AgentLoop Mode 合并

- [ ] `grep "AgentLoop.Mode\|enum Mode.*PLAN" src/main/java/com/bingtangcode/agent/AgentLoop.java` 返回 0 条（旧 Mode 枚举已删除）
- [ ] `grep "import.*PermissionMode" src/main/java/com/bingtangcode/agent/AgentLoop.java` 返回结果（AgentLoop 引用新枚举）
- [ ] `SystemReminderManager.onModeSwitch()` 参数类型为 `PermissionMode`（`grep "onModeSwitch" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 参数中不含 `AgentLoop.Mode`）
- [ ] `SessionManager` 中 `/plan` 命令调用 `agentLoop.setMode(PermissionMode.PLAN)`（源码审查）
- [ ] `SessionManager` 中 `/do` 命令调用 `agentLoop.setMode(PermissionMode.DEFAULT)`（源码审查）

## Shift+Tab 模式切换

- [ ] 在 DEFAULT 模式按 Shift+Tab → 切换为 ACCEPT_EDITS（端到端测试）
- [ ] 在 ACCEPT_EDITS 模式按 Shift+Tab → 切换为 PLAN（端到端测试）
- [ ] 在 PLAN 模式按 Shift+Tab → 切换为 BYPASS_PERMISSIONS（端到端测试）
- [ ] 在 BYPASS_PERMISSIONS 模式按 Shift+Tab → 切换为 DEFAULT（端到端测试）
- [ ] 切换后终端打印当前模式名称提示行（端到端观察）

## 配置文件格式

- [ ] `permissions.yaml` 中 `defaultMode` 支持四个值：`default`、`acceptEdits`、`plan`、`bypassPermissions`
- [ ] `rules` 列表中每条含 `tool`、`pattern`、`action` 三个字段
- [ ] `action` 仅接受 `allow` 或 `deny`
- [ ] `tool` 仅接受 `Bash`、`Read`、`Write`、`Edit`、`Glob`、`Grep` 六个值
- [ ] 全局配置路径：`~/.bingtangcode/permissions.yaml`（`grep "user.home" src/main/java/com/bingtangcode/permission/PermissionConfigLoader.java` 返回结果）
- [ ] 项目配置路径：`<project>/.bingtangcode/permissions.yaml`（源码审查）
- [ ] 本地配置路径：`<project>/.bingtangcode/permissions.local.yaml`（源码审查）

## 主流程编译

- [ ] `mvn clean compile` 返回 BUILD SUCCESS

## 端到端验收

- [ ] **黑名单拦截**：请求 `rm -rf /` → 立即拒绝，工具不执行，Agent 不终止
- [ ] **路径越界**：请求读 `/etc/passwd` → 沙箱拒绝，Agent 不终止
- [ ] **路径越界（符号链接）**：项目内有指向外部的软链接，读该链接 → 沙箱拒绝
- [ ] **规则 allow**：配置 `Bash(git *)` → allow，`git status` 静默放行
- [ ] **规则 deny**：配置 `Write(*.java)` → deny，写 `.java` 文件被拒
- [ ] **deny 优先**：同层有 `Bash(git *)` allow + `Bash(git push *)` deny，`git push` 被拒
- [ ] **default 模式 Ask**：无规则匹配的执行操作 → 弹确权块
- [ ] **一次放行**：选 ALLOW_ONCE → 本次通过，下次再弹窗
- [ ] **永久放行**：选 ALLOW_FOREVER → 写入 local 文件，后续自动放行
- [ ] **拒绝本次**：选 DENY_ONCE → 模型收到权限拒绝错误
- [ ] **bypassPermissions**：全 Allow 不走 Ask，但黑名单仍拦 `rm -rf /`
- [ ] **plan 模式**：只能调只读工具，写/执行不可见
- [ ] **Shift+Tab 循环**：四档切换正常，终端提示当前模式
- [ ] **/plan /do**：`/plan` 进入 PLAN，`/do` 回到 DEFAULT
- [ ] **拒绝不终止**：连续多次被拒，Agent 继续运行，不触发循环结束
