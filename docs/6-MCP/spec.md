# MCP 客户端集成规范 (Specification)

## 背景 (Background)
为了让 bingtangCode 具备良好的扩展性，并能够利用日益丰富的 Model Context Protocol (MCP) 服务生态，我们需要实现一个 MCP 客户端。该客户端能够在启动时自动发现并注册外部 MCP 服务提供的工具，使其对 AI Agent 透明可用（无感接入）。

## 目标 (Goals)
- **配置驱动的自动发现**：启动时从配置声明的 server 列表自动连接、列出工具、注册进工具中心，无需修改代码。
- **两种传输**：本地 server 走子进程标准输入输出管道（stdio）；远程 server 走 Streamable HTTP。
- **标准三步会话**：每个 server 一次连接经过 初始化握手 → 列出工具 → 按需调用工具（协议细节由官方 Java SDK 承载，不自研协议栈）。
- **无感适配**：发现到的远端工具包装成与内置工具一致的抽象，Agent 编排层与 provider 适配层均无需感知其来自远端。
- **命名空间隔离**：远端工具统一加 `mcp__<server>__<tool>` 前缀，杜绝与内置工具及多 server 间的重名冲突，并保留来源可追溯。
- **多 server 生命周期管理**：每个连接各自独立缓存与管理；单个 server 连接/初始化/列工具失败只跳过它自身，不影响其它 server、不影响启动；程序退出时统一、干净地关闭全部连接（含终止 stdio 子进程）。
- **两层配置合并**：server 列表从用户级（`~/.bingtangcode/config.yaml`）与项目级（`./config.yaml`）两个配置文件读取合并，项目级覆盖用户级同名 server。
- **凭据不落盘**：配置中环境变量与请求头的值支持从宿主环境变量展开（`${VAR}`），密钥不写进配置文件。
- **复用权限**：MCP 工具天然走权限系统的「规则 → 模式兜底 → 人在回路」链路，默认按命令执行类每次确认，自报只读（`readOnlyHint`）的按只读类放行并可并发；权限包**零改动**。
- **不破坏既有能力**：已有的会话、Loop、流式、缓存、规划、权限五层等行为不退化。

## 功能需求 (Functional Requirements)

- **F1: 两层 YAML 配置加载与合并**
  从**用户级** `~/.bingtangcode/config.yaml` 与**项目级** `./config.yaml` 两个文件读取 `mcp_servers` 段（map：key 为 server 名，value 为 server 定义）；按 server 名合并，**项目级同名 server 完整覆盖用户级**（不做字段级合并，避免半合并出畸形 server）。文件缺失视为空 `mcp_servers`；文件格式非法时**跳过该文件并 stderr 告警**，绝不致启动失败、不抛未捕获异常。`mcp_servers` 顶层不存在或为空，视为零个 MCP server，正常进 TUI。

- **F2: server 类型与必填字段校验**
  每个 server 定义自带 `type` 字段（**显式**：`stdio` 或 `http`），不靠字段嗅探判定类型。
  - `stdio` 类型必填 `command`（字符串）；可选 `args`（字符串数组）、`env`（字符串 map）。
  - `http` 类型必填 `url`（字符串）；可选 `headers`（字符串 map）。
  字段缺失或 `type` 非法时**跳过该 server 并 stderr 告警**，不影响其它 server 加载。

- **F3: 环境变量展开**
  `env` 与 `headers` 的**值**支持 `${VAR}` 形式从宿主环境变量取值；展开发生在配置加载阶段、不污染原始配置文件。**未定义的 `${VAR}` 展开为空串并 stderr 告警**，但不阻断该 server 启动。`command` / `args` 与 server 名、工具名**不做展开**。

- **F4: stdio 传输**
  对 `stdio` 类型 server，以 `command` + `args` 启动子进程；通过子进程的标准输入输出按 JSON-RPC 帧通信（由 SDK 完成）。`env` 与宿主进程环境合并后注入子进程（同名宿主变量被 `env` 覆盖）。`stderr` 透传给宿主 stderr 便于排查。子进程在退出时一并干净终止（关闭其 stdin → 等待 → 必要时发信号）。

- **F5: Streamable HTTP 传输**
  对 `http` 类型 server，以 `url` 为 endpoint 走 Streamable HTTP；配置中的 `headers` 注入每次 HTTP 请求。**不订阅服务器推送的独立 SSE 通道**，显式关闭 SSE 接收，只用请求-响应式工具调用，减少长连接维护成本。

- **F6: 标准三步会话**
  每个 server 建立后依次完成 **initialize 握手**（使用最新版协议 `"2025-06-18"` 交换 protocolVersion 与 capabilities）→ **`tools/list` 列出工具** → 进入按需 **`tools/call` 调用**阶段。整个协议层由官方 SDK 承载。本章只覆盖工具能力，**不订阅 / 不实现** MCP 的资源（resources）、提示词（prompts）、采样（sampling）、引导（roots）等其它能力。

- **F7: 工具适配（远端工具 ↔ 内置 Tool 抽象）**
  把 server 返回的每个远端工具包装成一个实现 BingtangCode `Tool` 接口的对象，注册进工具中心：
  - **名字**：`mcp__<server>__<tool>`（见 F8）。
  - **描述**：直接取远端 `description`（空则给一个含 server 名的兜底说明）。
  - **参数 schema**：把远端 `inputSchema` 转成 BingtangCode 的 `Map<String, Object>` 形式（透传 JSON Schema），不二次裁剪。
  - **只读性**：远端 `annotations.readOnlyHint==true` → `isReadOnly()==true`；其余（含字段缺失/非法）→ `false`（安全默认按有副作用处理）。
  - **执行**：调用时通过该 server 的会话发 `tools/call`；远端返回的 `content` 中 `type=text` 的文本块按顺序拼成 `Result.content`，远端 `isError==true` 映射为 `Result.isError==true`；非 text 块（image / audio 等）静默丢弃，并在首次/每次遇到时输出一次性 `System.err` 警告；调用过程中协议错误（连接断、超时、传输错）也转成 `isError==true` 的结构化错误**回灌给模型**（不抛 Java 异常给 Agent Loop，复用不中断会话的契约）。

- **F8: 工具命名空间与字符校验**
  所有 MCP 工具统一以 `mcp__<server>__<tool>` 命名。若工具名经前缀拼接后含 LLM 工具名禁用字符（非 `[A-Za-z0-9_-]`），**跳过该工具并 stderr 告警**。若注册时仍发生同名则后注册者保留并 stderr 告警。

- **F9: 启动同步连接 + 失败隔离（虚拟线程并发）**
  在进入 TUI 之前**同步**对所有配置中的 server 发起连接 + 握手 + 列工具（使用虚拟线程并发连接以缩短总时延）；**每个 server 的整个启动序列受 30s 超时约束**。任一 server 的连接 / 握手 / 列工具失败或超时**只跳过它自身**：启动不被阻断、其它 server 与内置工具集照常注册可用、stderr 给出该 server 的失败原因。不做延迟加载。

- **F10: 工具调用超时**
  每次 `tools/call` 复用 30s 超时（内置不可配）；超时转成 `isError==true` 的结构化错误结果回灌给模型，Agent Loop 继续。

- **F11: 退出时统一关闭**
  程序正常退出时，对所有已建立的会话统一调用关闭逻辑：stdio server 的子进程被干净终止，HTTP server 的会话释放。退出关闭兜底总超时时间设为 5s，防止退出卡死。

- **F12: 权限门禁无感复用**
  MCP 工具走现有判定拦截链路：
  - **黑名单**仅作用于内置 `bash` 命令串，对 MCP 工具不命中（自动跳过）。
  - **沙箱**仅作用于内置文件类工具，对 MCP 工具不适用（自动跳过）。
  - **规则引擎**按 `mcp__<server>__<tool>` 作为友好名匹配，用户可用精确名或带 `*` 的通配符（如 `mcp__github__*`）配置规则。
  - **模式兜底**：`isReadOnly()==true` 的 MCP 工具放行、可并发；其余（如写/副作用工具）在 safe 等模式下触发人在回路 Ask。
  权限包源码**零修改**。

## 非功能需求 (Non-functional Requirements)
- **N1: 失败隔离不阻塞**：单 server 任意阶段失败或卡住，只跳过它自身，不影响其它 server。
- **N2: 安全默认**：`readOnlyHint` 缺失或非法 → 非只读；`${VAR}` 未定义 → 空串；type 非法/字段缺失 → 跳过该 server。
- **N3: 跨协议一致**：同一 MCP server 在 Anthropic 与 OpenAI 两种 provider 下行为一致，适配层零修改。
- **N4: 权限零改动**：permission 包源码零修改；MCP 工具走既有判定链路。
- **N5: 不破坏既有能力**：已有的会话、Loop、流式、缓存等能力不退化。
- **N6: 凭据不落盘**：密钥仅通过 `${VAR}` 从宿主环境展开，敏感值不在任何日志或状态栏中输出或回显。
- **N7: 退出干净**：程序退出时安全清理子进程，总超时 5s 兜底。

## 不做的事 (Out of Scope)
- **MCP 资源（resources）、提示词（prompts）、采样（sampling）、引导（roots）**：本章只覆盖工具能力。
- **tools/list 变更通知 / 调用进度通知**：本章显式关闭 SSE 通道，工具集快照固定在启动时。
- **健康检查 / 自动重连 / 退避**：单连接挂掉就挂掉。
- **配置热加载 / 运行时增减 server**：重启才能应用新配置。
- **本地级 mcp_servers 配置层**：仅用户级与项目级两层合并。
- **mcp_servers 字段级合并**：按 server 名维度合并，项目级同名配置直接整对象覆盖。
- **`command` / `args` / 工具名 / server 名的变量展开**：仅 env / headers 的值展开 `${VAR}`。
- **OAuth 完整鉴权流程**：仅支持静态 token 注入。
- **自定义连接/调用超时**：30s 硬编码。
- **MCP 工具的黑名单与路径沙箱扩展**：这两层只对内置工具有意义，MCP 工具仅走规则 + 模式兜底 + 人在回路。由于外部进程环境和工具输入的多样性，防范外部文件的越权读取责任在于外部 MCP Server 本身的参数级沙箱保护以及用户的显式规则控制。
