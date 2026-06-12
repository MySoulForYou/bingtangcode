# checklist.md · 系统提示工程

## SystemPromptBuilder — 七模块组装

- [ ] `grep "class SystemPromptBuilder" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildIdentity" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildConstraints" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildTaskMode" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildActionExecution" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildToolUsage" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildTone" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildTextOutput" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "buildEnvSection" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "class EnvInfo\|record EnvInfo" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "一、身份" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] `grep "七、文本输出" src/main/java/com/bingtangcode/core/SystemPromptBuilder.java` 返回结果
- [ ] build() 方法输出中「身份」排在「系统约束」之前，「文本输出」排在「环境信息」之前（源码审查：方法内拼接顺序）
- [ ] `SystemPromptBuilder.java` 中不包含 `AgentLoop.Mode`、`SystemReminderManager` 的 import（职责隔离）

## SystemReminderManager — 频率控制

- [ ] `grep "class SystemReminderManager" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果
- [ ] `grep "getReminder" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果
- [ ] `grep "onModeSwitch" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果
- [ ] `grep "onRoundComplete" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果
- [ ] `grep "Plan模式：仅可用只读工具，专注于分析问题" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果（FULL 文本）
- [ ] `grep "Plan模式：仅可用只读工具" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果（SHORT 文本，注意不包含"专注于"）
- [ ] `grep "<system-reminder>" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果
- [ ] `grep "</system-reminder>" src/main/java/com/bingtangcode/core/SystemReminderManager.java` 返回结果
- [ ] `SystemReminderManager.java` 中不包含 "Do模式"（Do 模式不注入提醒）

## AgentLoop 集成

- [ ] `grep "SystemReminderManager" src/main/java/com/bingtangcode/agent/AgentLoop.java` 返回结果
- [ ] AgentLoop 构造函数参数列表含 SystemReminderManager（源码审查）
- [ ] `setMode()` 方法内调用 `reminderManager.onModeSwitch(mode)`（`grep "onModeSwitch" src/main/java/com/bingtangcode/agent/AgentLoop.java` 返回结果）
- [ ] `run()` 方法内，addUserMessage 之后有 injectReminder 调用（源码审查）
- [ ] while 循环内每轮 doRound 前有 injectReminder 调用（源码审查）
- [ ] 每轮结束后调用 `reminderManager.onRoundComplete()`
- [ ] `AgentLoop.java` 中不包含 `onAnomaly` 调用

## SessionManager — 模式切换触发注入

- [ ] `/plan` 命令处理后 AgentLoop.setMode(Mode.PLAN) 会触发 SystemReminderManager.onModeSwitch（源码审查：setMode 方法内已调用）
- [ ] `/do` 命令处理后 AgentLoop.setMode(Mode.FULL) 会触发 SystemReminderManager.onModeSwitch（源码审查）

## Main.java 装配

- [ ] `grep "SystemPromptBuilder" src/main/java/com/bingtangcode/Main.java` 返回结果
- [ ] `grep "SystemReminderManager" src/main/java/com/bingtangcode/Main.java` 返回结果
- [ ] `grep "EnvInfo" src/main/java/com/bingtangcode/Main.java` 返回结果
- [ ] 不再调用原 `buildSystemPrompt()`（`grep "private static String buildSystemPrompt" src/main/java/com/bingtangcode/Main.java` 无结果）
- [ ] `mvn clean compile` 返回 BUILD SUCCESS

## 工具描述双重强化

- [ ] EditFileTool.getDescription() 含"先使用 read_file 读取目标文件"或语义等价的提示（`grep "read_file\|读取" src/main/java/com/bingtangcode/tool/tools/EditFileTool.java` 返回结果）
- [ ] WriteFileTool.getDescription() 含"优先使用 edit_file"或语义等价的提示（`grep "edit_file\|edit_file" src/main/java/com/bingtangcode/tool/tools/WriteFileTool.java` 返回结果）

## 端到端验证

- [ ] 启动 bingtangCode，输入"你是谁"，模型回复包含"bingtangCode"或"终端 AI 编程助手"
- [ ] 输入"帮我读一下 pom.xml"，模型直接调 read_file 而非 Bash cat/sed
- [ ] 输入 `/plan`，终端显示 Plan Mode 切换提示
- [ ] Plan 模式下输入"帮我修改 pom.xml"，模型不调用 write_file/edit_file/execute_command
- [ ] Plan 模式下连续输入 4 轮任务，观察对话历史中第 1 轮注入完整 Plan 提醒，第 2、3 轮注入短标签，第 4 轮注入完整版（`grep "system-reminder"` 对日志文件检查注入频率）
- [ ] 输入 `/do`，终端显示 Do Mode 切换提示，全工具恢复可用
- [ ] 对话中问"当前工作目录是什么"，模型能从系统提示中获取并正确回答
