# 命令系统重构实施计划 (tasks.md)

## 任务列表

### 任务 1: 创建命令核心基础模型与接口
- **描述**: 新建命令类型枚举、执行上下文类与 UI 抽象接口。
- **影响文件**:
  - `[NEW]` [CommandType.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/CommandType.java)
  - `[NEW]` [CommandContext.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/CommandContext.java)
  - `[NEW]` [UIController.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/UIController.java)
- **依赖任务**: 无
- **参考资料定位**: 无

### 任务 2: 创建命令定义与注册中心
- **描述**: 新建命令信息实体类与注册管理类，实现声明式注册、查找、执行分发，以及在启动阶段执行重名/别名防撞校验（若重复则直接抛出 `IllegalStateException`）。
- **影响文件**:
  - `[NEW]` [Command.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/Command.java)
  - `[NEW]` [CommandHandler.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/CommandHandler.java)
  - `[NEW]` [CommandRegistry.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/CommandRegistry.java)
- **依赖任务**: 任务 1
- **参考资料定位**: 无

### 任务 3: 让 TerminalIO 实现 UIController 接口
- **描述**: 扩展 `TerminalIO` 使其实现 `UIController` 接口，提供包括添加系统日志、刷新状态栏、切入切出 Plan 模式等底层文本绘制实现。
- **影响文件**:
  - `[MODIFY]` [TerminalIO.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/tui/TerminalIO.java)
- **依赖任务**: 任务 1
- **参考资料定位**: [TerminalIO.java#L123-L157](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/tui/TerminalIO.java#L123-L157)

### 任务 4: 实现交互式选择列表（ActionListBox）
- **描述**: 新建一个终端单选列表框组件，用于加载历史会话并响应键盘上下方向键、Enter 键选择。
- **影响文件**:
  - `[NEW]` [ActionListBox.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/ActionListBox.java)
- **依赖任务**: 任务 3
- **参考资料定位**: [SessionManager.java#L163-L214](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SessionManager.java#L163-L214) 菜单盒实现

### 任务 5: 实现 JLine3 Completer 补全器集成
- **描述**: 编写一个自定义 JLine `Completer`，根据命令注册中心注册的命令和别名，过滤隐藏命令，对输入内容按 Tab 键进行补全。
- **影响文件**:
  - `[MODIFY]` [TerminalIO.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/tui/TerminalIO.java)
- **依赖任务**: 任务 2, 任务 3
- **参考资料定位**: [TerminalIO.java#L70-L77](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/tui/TerminalIO.java#L70-L77) 实例化 LineReader

### 任务 6: 编写 12 个内置命令的 Handler 逻辑
- **描述**: 集中编写这 12 个指令的具体执行逻辑（包括清空重置对话的 `/clear`，打开列表的 `/resume`，两列对齐输出状态的 `/status`，输出已加载记忆文件名的 `/memory`，以及 `/review` 等）。
- **影响文件**:
  - `[NEW]` [BuiltInCommands.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/command/BuiltInCommands.java) (或者在各自命令类中定义)
- **依赖任务**: 任务 2, 任务 4
- **参考资料定位**: [SessionManager.java#L52-L93](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SessionManager.java#L52-L93) 的 if 分支

### 任务 7: 命令分流与主流程集成
- **描述**: 修改 `Main.java` 和 `SessionManager.java`，将初始化好的注册中心注入主循环，重构原有的 `if-else` 段，将输入统一分流到命令系统执行，对未知命令带上 `/help` 引导。
- **影响文件**:
  - `[MODIFY]` [Main.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/Main.java)
  - `[MODIFY]` [SessionManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SessionManager.java)
- **依赖任务**: 任务 5, 任务 6
- **参考资料定位**: [SessionManager.java#L39-L93](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/core/SessionManager.java#L39-L93)

### 任务 8: 端到端验证与自动化单元测试
- **描述**: 编写自动化测试验证注册表冲突检测、解析器切分、12 个命令行为，并启动应用进行手动交互测试（测试 Tab 补全和 `/resume` 菜单列表）。
- **影响文件**:
  - `[NEW]` [CommandSystemTest.java](file:///Users/laq/Documents/bingtangcode/src/test/java/com/bingtangcode/command/CommandSystemTest.java)
- **依赖任务**: 任务 7
- **参考资料定位**: 无
