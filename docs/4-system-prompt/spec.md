# spec.md · 系统提示工程

## 背景

当前 bingtangCode 的系统提示是 Main.java 中硬编码的三行字符串，包含身份描述 + 模型信息 + 三条工具规则。它有三个问题：

1. **无结构** — 所有指令混在一起，加新规则只能往字符串后面追加，无法按职责组织
2. **无缓存优化** — 稳定指令和动态信息（provider 名、model 名）混在同一字符串里，整个系统提示每次都可能变，无法稳定命中 API 缓存
3. **无运行中注入通道** — Plan Mode 只能靠过滤工具集来约束模型行为，模型不知道自己处于 plan 模式，遇到约束时可能反复试错

## 目标用户

- bingtangCode 终端用户（受益于模型行为更一致、响应更快）
- bingtangCode 开发者（有新规则时知道插到哪个模块，不用通读整个提示词）

## 能力清单

1. **七模块系统提示组装** — SystemPromptBuilder 按优先级组装 7 个固定模块（身份、系统约束、任务模式、动作执行、工具使用、语气风格、文本输出），模块间空行分隔，整体按优先级从前到后拼接
2. **稳定/动态内容分流** — 7 个固定模块走 SYSTEM 消息（可缓存通道）；环境信息（工作目录、平台、日期、git 状态）作为 SYSTEM 消息的第二段或独立消息；模式切换指令、自定义指令、Skill 列表走 USER 角色 `<system-reminder>` 消息通道
3. **双重关键规则强化** — 关键规则同时出现在系统提示（通用规则）和工具描述（工具特定规则）中，提高模型遵守率。关键规则包括：编辑前必先读、优先用专用工具而非 Bash、只读工具可并发副作用工具串行
4. **运行时指令注入** — 用 `<system-reminder>` 标签包裹的 USER 角色消息在运行中注入补充指令，模型将其识别为系统指令而非用户对话，不污染 API 缓存前缀
5. **规划模式指令按轮次注入** — 通过 `<system-reminder>` 消息承载规划模式提醒。首轮注入完整指令，每 3 轮重复一次完整版，中间轮次注入极短标签。模式跨轮保持，`/plan` 进入、`/do` 退出
6. **自定义指令优先级框架** — 预留自定义指令模块位置，优先级：会话 `/set` > 项目级 `bingtang.md` > 全局配置。本章只定义结构和注入优先级，不实现文件加载
7. **可选模块注入框架** — 系统提示末尾按序排列可选模块（自定义指令、已激活 Skill、长期记忆），内容为运行时通过消息通道动态注入，不写入固定系统提示

## 非功能要求

- 模块低耦合 — 增删模块或调整模块内规则不影响其他模块，不改变 SystemPromptBuilder 的组装逻辑结构
- 向后兼容 — 现有对话流程不变，不加系统提示模块时行为与当前一致
- 模式切换 < 1ms — 只修改 AgentLoop 内部字段，不涉及 IO 或网络

## 设计骨架

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SystemPromptBuilder                           │
│  build(): String                                                     │
│    → 一、身份                                                        │
│    → 二、系统约束                                                    │
│    → 三、任务模式                                                    │
│    → 四、动作执行                                                    │
│    → 五、工具使用                                                    │
│    → 六、语气风格                                                    │
│    → 七、文本输出                                                    │
│    → 环境信息（动态拼入）                                            │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                      SystemReminderManager                           │
│                                                                      │
│  getReminder(mode, roundCount, anomaly): String | null               │
│    - 首轮 → FULL（完整指令）                                         │
│    - 每 3 轮 → FULL                                                  │
│    - 中间轮次 → SHORT（极短标签）                                    │
│    - 异常事件 → FULL（立即覆盖）                                     │
│                                                                      │
│  FULL:    <system-reminder>Plan模式：仅可用只读工具，专注于分析      │
│           问题、制定计划，不要尝试修改文件或执行命令。</system-reminder>│
│  SHORT:   <system-reminder>Plan模式：仅可用只读工具</system-reminder> │
│  ANOMALY: <system-reminder>异常类型 + 纠偏指令</system-reminder>     │
└─────────────────────────────────────────────────────────────────────┘

注入流程（AgentLoop 内）:

  用户输入 → addUserMessage(input)
           → reminder = reminderManager.getReminder()
           → if reminder: addMessage(USER, reminder)
           → doRound()
              → buildApiMessages() 合并连续 USER 消息
              → LLM 看到:
                 SYSTEM: [7模块 + 环境信息]
                 USER:   <system-reminder>Plan模式...</system-reminder>
                         [用户实际输入]
           → 检测异常 → reminderManager.onAnomaly() 标记
           → reminderManager.onRoundComplete()
```

### 消息结构

```
┌──────────────────────────────────────────┐
│ SYSTEM 消息（可缓存前缀）                  │
│ ┌──────────────────────────────────────┐ │
│ │ 一、身份                              │ │
│ │ 二、系统约束                          │ │
│ │ 三、任务模式                          │ │
│ │ 四、动作执行                          │ │
│ │ 五、工具使用                          │ │
│ │ 六、语气风格                          │ │
│ │ 七、文本输出                          │ │
│ │ ---                                  │ │
│ │ 环境信息（动态拼接在末尾）             │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
┌──────────────────────────────────────────┐
│ USER 消息（含 <system-reminder>，不缓存） │
│ ┌──────────────────────────────────────┐ │
│ │ <system-reminder>Plan模式：...       │ │
│ │ </system-reminder>                   │ │
│ │                                      │ │
│ │ 帮我读一下 pom.xml                    │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

### SystemPromptBuilder

```java
class SystemPromptBuilder {
    String build(String providerName, String modelName, EnvInfo env);

    // 各模块独立方法，方便单独测试和修改
    private String buildIdentity();
    private String buildConstraints();
    private String buildTaskMode();
    private String buildActionExecution();
    private String buildToolUsage();
    private String buildTone();
    private String buildTextOutput();

    // 环境信息
    private String buildEnvSection(EnvInfo env);
}
```

### SystemReminderManager

```java
class SystemReminderManager {
    void onModeSwitch(Mode mode);   // 标记首轮，重置轮次计数器
    String getReminder();            // 返回本轮应注入的提醒内容，FULL 模式返回 null
    void onRoundComplete();          // 轮次计数 +1
}
```

## Out of Scope

- 项目指令文件加载（bingtang.md / CLAUDE.md 解析和注入）
- 自动记忆系统（长期记忆的读写和检索）
- MCP 协议接入（外部工具服务器的集成）
- Skill 的激活/停用机制和 Skill 描述生成
- 自动化评估（用 LLM 评估另一 LLM 的输出质量）
- 上下文压缩（对话历史超长时的截断或摘要策略）
- 系统提示版本管理（多版本并存、A/B 测试）
- 用户自定义模块（用户直接编辑系统提示模块内容）
