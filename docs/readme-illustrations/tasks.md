# README 流程与结果图示说明 — 任务拆解与开发计划（Tasks）

## 任务列表

### Task 1: 创建图示资源存放目录
- **说明**：在项目根目录下创建 `fig/readme/` 目录，用于保存所有新生成的 3D 风格图片资源。
- **影响文件**：无（新建目录）
- **依赖任务**：无
- **参考资料定位**：无

### Task 2: 生成 3D 卡通微立体风 ReAct Agent Loop 流程图 (无人物，拟物图标)
- **说明**：调用内置 AI 画图工具 `generate_image`，以暖乳白色为背景、3D 卡通微立体圆角卡片、Observation 反馈循环流、配 3D 拟物图标（如发光脑力、盾牌、对话气泡）为特征，生成流程图。
- **影响文件**：覆盖 `fig/readme/react_agent_loop.png`
- **依赖任务**：Task 1
- **参考资料定位**：[AgentLoop.java:L47-107](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/agent/AgentLoop.java#L47-L107)

### Task 3: 生成 3D 卡通微立体风 PermissionGate 拦截图 (无人物，拟物图标)
- **说明**：调用内置 AI 画图工具 `generate_image`，以暖乳白色为背景、3D 立体卡片层叠防线、配 3D 拟物图标（安全盾牌、保险箱、清单本、开关、指示手指）与左侧彩色药丸状实例标签为特征，生成拦截图。
- **影响文件**：覆盖 `fig/readme/permission_gate_flow.png`
- **依赖任务**：Task 1
- **参考资料定位**：[PermissionGate.java:L51-117](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/permission/PermissionGate.java#L51-L117)

### Task 4: 生成 3D 场景化终端交互概念效果图
- **说明**：调用内置 AI 画图工具 `generate_image`，以 3D 拟物角色坐姿敲代码、屏幕浮空磨砂玻璃对话框展现 HITL 弹窗为主要特征，生成终端交互概念效果图。
- **影响文件**：新建/覆盖 `fig/readme/terminal_interaction.png`
- **依赖任务**：Task 1
- **参考资料定位**：[README.md:L117-134](file:///Users/laq/Documents/bingtangcode/README.md#L117-L134) (命令行及运行截图)

### Task 5: 在 README.md 中嵌入图示并完善文字说明
- **说明**：编辑项目的 `README.md`，在相应段落插入这三张图片，并使用文字对新 3D 图示所表达的业务流程进行辅助说明。
- **影响文件**：[README.md](file:///Users/laq/Documents/bingtangcode/README.md)
- **依赖任务**：Task 2, Task 3, Task 4
- **参考资料定位**：[README.md:L19-62](file:///Users/laq/Documents/bingtangcode/README.md#L19-L62)

### Task 6: 接入主流程与端到端验证
- **说明**：检查 README 中引用的图片路径是否完全正确，确保在本地 Markdown 预览及 GitHub 规范下均能正常显示；验证图片的文件体积和尺寸合理性。
- **影响文件**：[README.md](file:///Users/laq/Documents/bingtangcode/README.md)
- **依赖任务**：Task 5
- **参考资料定位**：无
