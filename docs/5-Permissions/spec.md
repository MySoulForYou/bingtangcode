# spec.md · 五层防御权限系统

## 背景

bingtangCode 当前只有两个安全机制：路径越界检查（硬编码在各文件工具中）和 Bash 命令执行前的一次性确认弹窗。没有可配置规则、没有分级信任、没有记忆能力。模型每次执行写操作或 shell 命令时用户必须手动确认，确认过一次后下次同样的操作还得再确认，无法沉淀信任。

本章给 bingtangCode 装上五层防御权限系统，让用户可渐进式建立信任，同时保留对高危操作的硬阻断能力。

## 目标用户

在终端里用 bingtangCode 做编程任务的开发者——他们希望既能安全地让 AI 自主执行操作，又能按自己的风险偏好调整管控力度。

## 能力清单

1. **危险命令黑名单** — 用正则硬编码一组高危 Bash 命令模式，在工具执行前拦截，不可被任何配置或模式绕过
2. **路径沙箱** — 所有文件类工具统一过沙箱：解析符号链接拿到真实路径后做项目根目录前缀判断，拒绝越界访问
3. **可配置规则引擎** — 用户通过 YAML 文件声明规则，每条规则为「工具友好名 + 主参数模式 + allow/deny」，模式支持精确匹配和 glob（含 `**` 递归通配）
4. **三层规则文件** — 规则按用户全局（`~/.bingtangcode/permissions.yaml`）、项目级（`<project>/.bingtangcode/permissions.yaml`）、项目本地（`<project>/.bingtangcode/permissions.local.yaml`）三层存放；优先级：本地 > 项目 > 用户
5. **四档权限模式** — default（只读 Allow，写/执行 Ask）、acceptEdits（只读/写 Allow，执行 Ask）、plan（同 default 但仅暴露只读工具）、bypassPermissions（全部 Allow，不走 Ask，但黑名单和沙箱仍拦截）；模式切换方式：配置文件 defaultMode 字段（启动生效）+ `/mode` 菜单选择 + `/plan` 与 `/do` 命令 + Shift+Tab 循环
6. **人在回路确认** — 规则未命中时暂停 token 流，弹出多行选项块，支持箭头键动态选择：允许本次（1/y）、永久允许（2，写入 permissions.local.yaml）、拒绝本次（3/n/d）；无超时，等待用户明确选择；默认高亮「允许本次」
7. **权限不被拒终止循环** — 工具被拒时返回结构化错误（含拒因和匹配到的规则来源），模型可据此调整策略继续 Agent Loop；连续 5 轮全工具被拒则触发 PERMISSION_DENIED_LOOP 终止
8. **规则优先级体系** — 同层规则 deny 优先于 allow；同一 effect 内按书写顺序优先匹配；跨层按 local > project > user 逐层判断
9. **模式合并** — 现有 AgentLoop.Mode（PLAN/FULL）与权限模式合并为统一的 PermissionMode 枚举，AgentLoop 直接使用权限模式驱动工具过滤和行为决策

## 非功能要求

- 权限检查在 `DialogueManager.executeToolBatch()` 中集中执行，不侵入单个 Tool 实现
- 路径匹配使用项目相对路径，引擎自动将绝对路径转换为相对路径后再匹配
- 黑名单正则编译一次后缓存，避免每次执行重复编译
- 配置文件解析失败时不崩溃，打印告警后按最安全策略（default 模式 + 空规则集）运行
- 人在回路交互不阻塞 EventBus 线程，暂停 token 流的方式是阻塞 Agent Loop 当前迭代而非丢弃事件

## 设计骨架

```
用户输入 → AgentLoop.run()
               │
               ▼
         selectTools()  ← 根据 PermissionMode 过滤工具列表
               │
               ▼
         dialogue.doRound(tools, ...)
               │
               ▼
         executeToolBatch(toolCalls)
               │
               ▼
    ┌──────────────────────────┐
    │  PermissionGate.check()  │  ← 五层防御集中拦截点
    │                          │
    │  Layer 1: 黑名单 (硬编码)  │     匹配 → DENY（不可绕过）
    │  Layer 2: 路径沙箱        │     越界 → DENY
    │  Layer 3: 规则引擎        │     匹配 → ALLOW / DENY
    │  Layer 4: 权限模式兜底    │     未命中 → 模式决定 Allow/Ask
    │  Layer 5: 人在回路        │     Ask → 用户交互确认
    └──────────────────────────┘
               │
          ALLOW ▼
         ToolExecutor.execute()
               │
          DENY ▼
         返回结构化错误 ToolResult → 模型调整策略继续循环
```

### 权限模式枚举

```
PermissionMode:
  DEFAULT           — 只读 Allow，写/执行 → Ask
  ACCEPT_EDITS      — 只读/写 Allow，执行 → Ask
  PLAN              — 同 DEFAULT，但 selectTools() 只返回 isReadOnly==true 的工具
  BYPASS_PERMISSIONS — 全部 Allow（规则引擎正常执行，黑名单/沙箱仍拦截）
```

### 三层规则文件

```
~/.bingtangcode/permissions.yaml        ← 用户全局默认
<project>/.bingtangcode/permissions.yaml ← 项目共享（提交 Git）
<project>/.bingtangcode/permissions.local.yaml ← 本地覆盖（不提交 Git，永久放行写入此处）
```

YAML 结构：
```yaml
defaultMode: default   # default | acceptEdits | plan | bypassPermissions
rules:
  - tool: Bash
    pattern: "git *"
    action: allow
  - tool: Bash
    pattern: "npm *"
    action: allow
  - tool: Write
    pattern: "*.md"
    action: deny
  - tool: Read
    pattern: ".env"
    action: deny
```

### 工具内部名 → 规则友好名映射

| 内部名              | 友好名  | 匹配的主参数       |
|---------------------|---------|-------------------|
| `read_file`         | Read    | file_path         |
| `write_file`        | Write   | file_path         |
| `edit_file`         | Edit    | file_path         |
| `execute_command`   | Bash    | command           |
| `find_files`        | Glob    | directory         |
| `search_content`    | Grep    | directory         |

### 规则匹配语义

- `pattern` 中包含 `*` 或 `**` → glob 匹配（`*` 匹配单层，`**` 匹配任意层级）
- `pattern` 中不含 `*` → 精确字符串匹配
- 路径类参数：引擎将绝对路径转为项目相对路径后再匹配，模式始终针对相对路径
- 命令类参数：直接对命令字符串匹配

### 优先级裁决流程

```
对每个工具调用:
  1. 查 permissions.local.yaml → 命中则返回
  2. 查 <project>/.bingtangcode/permissions.yaml → 命中则返回
  3. 查 ~/.bingtangcode/permissions.yaml → 命中则返回
  4. 所有规则未命中 → 按 PermissionMode 默认行为处理
     - DEFAULT:       isReadOnly? → Allow : → Ask(人在回路)
     - ACCEPT_EDITS:  Bash? → Ask : → Allow
     - PLAN:          isReadOnly? → Allow : → Ask
     - BYPASS:        → Allow（黑名单/沙箱仍可拦截）
```

同层规则内的优先级：
1. deny 优先于 allow（两条规则同时命中时 deny 获胜）
2. 同 effect 内按文件中书写顺序优先匹配（先写的先生效）

### 人在回路交互

```
┌─ 权限确认 ───────────────────────────────┐
│                                          │
│  Bash: git push --force origin main      │
│  未匹配任何规则，是否允许？                 │
│                                          │
│  ▸ 1. 允许本次         (ALLOW_ONCE)       │
│    2. 永久允许         (ALLOW_FOREVER)     │
│    3. 拒绝本次         (DENY_ONCE)         │
│                                          │
│  快捷键: [1/y] 允许  [3/n/d] 拒绝        │
│  ↑/k 上移  ↓/j 下移  Enter 确认          │
└──────────────────────────────────────────┘
```

- ALLOW_ONCE：仅此次执行放行，不写入任何文件
- ALLOW_FOREVER：写入 `permissions.local.yaml` 一条 allow 规则，永久生效
- DENY_ONCE：仅此次拒绝，返回结构化错误给模型
- 无超时，等待用户明确选择
- 弹窗期间暂停 token 流（阻塞 Agent Loop 当前迭代）

### 被拒错误信息格式

```
权限拒绝: Bash(git push --force) 被 deny 规则拦截
  匹配规则: [local] Bash(git push *) → deny
  建议: 修改命令参数或请求用户调整规则
```

### 黑名单正则清单（硬编码，不可配置）

| 模式 | 说明 |
|------|------|
| `rm\s+-rf\s+/` | 递归强制删除根目录 |
| `rm\s+-rf\s+~` | 递归强制删除家目录 |
| `rm\s+-rf\s+\*` | 递归强制删除当前目录所有内容 |
| `sudo\s+` | 提权执行（任意命令加 sudo 前缀） |
| `curl\s+.*\|\s*(ba)?sh` | curl 管道到 shell 执行 |
| `wget\s+.*-O\s*-\s*\|\s*(ba)?sh` | wget 管道到 shell 执行 |
| `chmod\s+777\s+` | 开放全部权限 |
| `chmod\s+-R\s+777` | 递归开放全部权限 |
| `chown\s+-R\s+` | 递归更改所有者 |
| `mkfs\.` | 格式化文件系统 |
| `dd\s+if=` | 磁盘直接读写 |
| `>\/dev\/sd[a-z]` | 覆盖磁盘设备 |
| `:(\(\)\s*\{|:&\}\s*;:)` | Fork 炸弹 |
| `shutdown\s+` | 系统关机 |
| `reboot\s+` | 系统重启 |
| `init\s+[0-6]` | 切换运行级别 |
| `systemctl\s+(stop|disable|mask)\s+` | 停止/禁用系统服务 |
| `git\s+push\s+--force\s+.*main` | force push 到 main |
| `git\s+push\s+--force\s+.*master` | force push 到 master |

## Out of Scope

- 网络请求限制（URL 白名单/黑名单）
- 资源配额（CPU/内存/磁盘使用上限）
- 审计日志（工具调用不落盘记录）
- MCP 协议工具权限（仅限内置 6 个工具）
- 并发工具调用的权限竞态处理（单次 checks 已覆盖同一批次中多个 tool call）
- 规则热加载（修改配置文件需重启生效）
- 权限模式的时间窗口自动切换
- 多用户/角色权限
