# flow-guide.md · bingtangCode MCP 客户端集成

面向小白。读完你能理解：bingtangCode 如何通过 Model Context Protocol (MCP) 扩展工具集、本地 stdio 与远程 Streamable HTTP 的传输工作原理、三步会话流程、工具适配器包装、Java 21 虚拟线程的深度应用、以及安全权限如何对接。

---

## 一、MCP 核心端到端流程：从配置加载到执行回复

为了帮助你建立全局观，以下是 `bingtangCode` 从读取配置文件、启动连接、直至大模型在对话中发起工具调用并获得回复的完整、连贯的生命周期：

### 阶段 1：启动、初始化与工具发现（一次性完成）

1. **加载配置与环境变量展开**：
   * 主程序 `Main` 启动，首先实例化 `ConfigManager`。
   * `ConfigManager` 同时读取全局用户配置 `~/.bingtangcode/config.yaml` 与项目本地配置 `config.yaml` 进行深度合并。
   * 合并后的配置经过 `EnvExpander`，将所有带有 `${VAR}` 占位符的配置（如 API 密钥、Header Token 等）从操作系统的环境变量中提取真实值进行展开替换。

2. **并发拉起 MCP 传输通道（虚拟线程池）**：
   * `McpManager.start(config)` 被调用，它创建了一个虚拟线程池 `Executors.newVirtualThreadPerTaskExecutor()`。
   * 针对配置中的每一个 MCP 服务，都会启动一个独立的虚拟线程并行发起连接：
     * **Stdio 模式**：通过 Java 的 `ProcessBuilder` 启动后台子进程（例如执行 `npx -y @modelcontextprotocol/server-sqlite`），并建立本地标准输入输出（stdin/stdout）的管道连接。
     * **HTTP 模式**：实例化 `HttpTransport` 并配置对应的远端服务器 URL 地址。

3. **三步会话握手与工具拉取**：
   * 并发运行的每个会话执行 `session.initialize(30)` 握手协议：
     * **第 1 步**：发送 `initialize` 请求通报客户端信息。
     * **第 2 步**：发送 `notifications/initialized` 通告就绪。
     * **第 3 步**：发送 `tools/list` 请求，把服务端暴露的工具“清单”（名称、参数 Schema、是否只读等元数据）拉取到客户端。

4. **适配器实例化与命名映射**：
   * 针对每个拉取到的工具，`McpManager` 调用 `McpToolAdapter.create` 实例化一个工具包装器（`McpToolAdapter`）。
   * 适配器把工具名称转换为全局唯一的格式：`mcp__<server_name>__<tool_name>`（例如 `mcp__sqlite__query`）。
   * 重点：**在实例化时，这个适配器对象中已经永久注入了它所对应的那个 MCP 服务的 `session` 管道引用**。
     * *设计意图*：因为 MCP 工具的真实执行逻辑在外部进程或网络中，适配器本身只是一个“代理空壳”，必须依赖注入的 `session` 作为通信管道来发送 RPC 请求并接收回包；而本地内置工具（如 `ReadFileTool`）的代码直接在当前 JVM 进程中本地执行，因此不需要任何会话或通信管道。

5. **注册进全局工具表**：
   * 主程序循环遍历这些适配器对象，将其统一注册进 `ToolRegistry` 注册表中。
   * 注册表本质是一个 `LinkedHashMap`。调用 `register()` 后，Map 中就新增了一条映射关系：
     `"mcp__sqlite__query"` (字符串 Key) ──→ `McpToolAdapter` 实例 (存储在 Map 的 Value 中)。
   * 程序随即将这批已注册工具的 Schema 作为“工具说明书”随系统提示词一同传给大模型。

---

### 阶段 2：运行时对话、安全审计与工具执行（按需多次循环）

6. **用户输入问题，模型决策调用**：
   * 用户在终端输入：“*查询数据库中所有用户的信息*”。
   * 大模型阅读了我们通过系统提示词发送的“工具说明书”，判断需要调用该工具，并向客户端返回如下 JSON 指令：
     `{ "name": "mcp__sqlite__query", "parameters": { "sql": "SELECT * FROM users;" } }`
   * 主循环收到指令，从 `ToolRegistry` 的 Map 中执行 `get("mcp__sqlite__query")`，通过哈希匹配在 $O(1)$ 时间内极速找到之前注册的那个 `McpToolAdapter` 对象实例。

7. **五层权限门禁校验（PermissionGate）**：
   * 调用实例被传递至 `PermissionGate.check()` 进行安全审计：
     * **Layer 1 黑名单 & Layer 2 路径沙箱**：由于工具名称是 `mcp__*` 外部工具，而非本地内置命令行或文件读写工具，**自动无感跳过（豁免）**。
     * **Layer 3 规则引擎**：依据本地 `permissions.yaml` 文件中的配置查找是否命中了放行/拒绝规则（如配置了 `pattern: "mcp__sqlite__*"`）。
     * **Layer 4 & 5 模式兜底与人在回路**：检查工具是否只读。如果是只读工具直接放行；如果是非只读工具（即具有副作用的修改动作），程序会挂起，在终端控制台向用户强制展示 `[y/N]` 交互弹窗进行手动授权确认。

8. **并发超时保护运行（ToolExecutor）**：
   * 授权通过后，主线程将任务委托给 `ToolExecutor` 在独立的后台线程中执行：`executor.submit(() -> tool.execute(params))`。
   * `ToolExecutor` 监控执行时间，限制每个工具执行不得超过 30 秒，防止远端或本地工具无响应导致程序永远卡死。

9. **动态多态派发（McpToolAdapter.execute）**：
   * Java 虚拟机（JVM）识别出 `tool` 变量指向的实例类类型是 `McpToolAdapter`，在运行时自动动态跳转去运行 `McpToolAdapter.execute(...)` 里的协议传输代码（如果是本地工具，它则会多态跳转去运行本地类如 `ReadFileTool` 的对应本地读文件代码）。

10. **管道/网络通信传输与后台监听**：
    * `McpToolAdapter` 使用其永久持有的 `session` 发送 JSON-RPC 调用指令：`session.callTool("query", params)`。
    * **StdioTransport**：将调用请求单行序列化为 JSON 文本，直接写入后台 Node.js 进程的标准输入（stdin）管道流。
    * 后台 Node.js 进程读取到请求，连接数据库执行 SQL，并将执行结果单行 JSON 输出至标准输出（stdout）中。
    * `StdioTransport` 在启动时开启的一个**后台守护虚拟线程**时刻监听着该管道，读到 stdout 的回包数据后，根据 JSON 中的 `id` 自动将结果交还给正在等待的调用线程。

11. **结果解析与状态回灌**：
    * 适配器获取到调用结果，对内容块进行解析。若是图像等非文本数据包，程序自动予以过滤丢弃，并在首次过滤时在 stderr 打印一次性警告。
    * 拼装完成的文本结果以 `ToolResult` 形式逐层返回给大模型。
    * 大模型拿到查询到的数据库结果，理解后在终端流式回复用户：“*数据库里的用户有张三和李四。*”

---

## 二、MCP 客户端解决什么问题

### 之前的问题

- **工具集完全写死**：BingtangCode 内部仅硬编码了 6 个内置核心工具（读/写/改文件、运行命令、查找文件、内容搜索）。
- **扩展能力固定**：想要接入任何第三方 API、GitHub、Slack 或内部接口，必须修改 BingtangCode 源码并重新编译，扩展能力锁死在编译期。

### 现在的能力

| 能做 | 不能做 |
|------|--------|
| 配置驱动的自动工具发现 | MCP 资源（resources）与提示词（prompts）等非工具能力 |
| 本地子进程管道传输 (stdio) | HTTP 传输下的 SSE 独立长连接推送与通知 |
| 远程轻量同步传输 (Streamable HTTP) | MCP 服务端健康状态检查与掉线自动重连 |
| 两层 YAML 配置文件加载合并 | 运行时动态热加载配置（需重启生效） |
| `${VAR}` 宿主环境变量展开（凭据不落盘）| 针对外部 MCP 工具实施客户端本地文件路径沙箱拦截 |
| 工具名前缀隔离 (`mcp__<server>__<tool>`) | 自动 OAuth 换 Token 鉴权（仅支持 Headers 静态 Token） |
| 虚拟线程并发初始化（30s 独立超时） | |
| 退出自动彻底清理子进程（5s 兜底） | |
| 外部工具零改动复用本地五层权限系统 | |

---

## 三、架构设计与工具注册流

### 启动初始化流程

```
Main.main() 启动
      │
      ▼
ConfigManager 构造
      ├─ 读取并合并用户级 (~/.bingtangcode/config.yaml) 与项目级 (./config.yaml) 中的 mcp_servers
      ├─ 字段有效性校验：非 stdio/http 丢弃；stdio 缺少 command 丢弃；http 缺少 url 丢弃
      └─ EnvExpander 展开 env 和 headers 中的 ${VAR} 占位符（未定义变量警告并置空）
      │
      ▼
McpManager.start() 并发初始化
      │ (使用 Executors.newVirtualThreadPerTaskExecutor() 并发)
      ├─ Thread-1 (serverA) ────────────→ 构造 Transport ──→ initialize() 握手 ──→ tools/list 拉取 ──→ 成功
      ├─ Thread-2 (serverB, 挂起超时) ──→ 30s 超时抛出异常 ──→ 错误隔离并跳过，不阻断主流程启动 ─→ 失败
      │
      ▼
适配并注册工具
      ├─ 校验工具命名是否满足正则 ^[A-Za-z0-9_-]+$（违规跳过并告警）
      ├─ 封装为 McpToolAdapter 实例（前缀命名为 mcp__<server>__<tool>）
      └─ 批量注册到 ToolRegistry 中
      │
      ▼
Main 注册 JVM Shutdown Hook
      └─ 在程序退出时，McpManager.close() 被调用（5s 退出超时兜底，并发关闭所有活跃会话/子进程）
```

### 运行期工具调用流

```
Agent 发起工具调用请求 (mcp__database__get_user)
                     │
                     ▼
        PermissionGate.check() 检查 ── (Layer 1 黑名单 & Layer 2 路径沙箱自动跳过)
                     │
                     ├─ Layer 3: 规则引擎匹配（支持精确名与通配符，如 mcp__database__*）
                     ├─ Layer 4: 模式兜底决策（若 isReadOnly() == true 放行，否则流向人在回路）
                     └─ Layer 5: 人在回路（如果是非只读工具，强制提示用户 [y/N] 确认）
                     │
                ALLOW▼
            ToolExecutor.execute()
                     │
                     ▼
          McpToolAdapter.execute()
                     ├─ 启动 30s 开发调用超时保护
                     ├─ 将参数 params 映射封装为参数集 arguments
                     └─ McpSession.callTool("get_user", arguments)
                           │
                           ▼
                       Transport
                           ├─ StdioTransport: 单行 JSON 写 stdin ──→ 后台线程读 stdout 匹配 ID 返回
                           └─ HttpTransport: OkHttp 发送 POST ──→ 同步读取响应 Response 返回
                           │
                           ▼
                     适配响应结果
                           ├─ text 内容块按顺序拼接成 string
                           ├─ 非 text 内容块（如 image）静默丢弃（每个工具首次丢弃时 stderr 告警）
                           └─ 协议错误/超时/远端 isError==true 封装为 ToolResult(isError=true) 
                     │
                     ▼
            返回结果给大模型，Loop 继续
```

---

## 四、三步连接会话

MCP 客户端与任何服务端的通信都会在启动时，严格执行以下标准的**三步会话握手**流程：

1. **第 1 步：`initialize` 握手**  
   客户端发送 `initialize` 请求，携带客户端信息（name="bingtangCode", version="0.5.0"）与协议版本号（最新版标准 `"2025-06-18"`）。服务端响应其协议版本、Server 元数据及它的 capabilities 能力。
2. **第 2 步：`notifications/initialized` 通告**  
   客户端收到初始响应并确认版本兼容后，向服务端发送单向的 `notifications/initialized` 通知，宣告连接正式就绪。
3. **第 3 步：`tools/list` 获取工具**  
   客户端发送 `tools/list` 请求，拉取服务端暴露的所有可用工具的元数据（包含名称、描述、参数 JSON Schema 以及 `readOnlyHint` 标识）。

---

## 五、核心类与文件说明

| 模块/类名 | 文件路径 | 职责 |
|------|------|--------|
| **EnvExpander** | `config/EnvExpander.java` | 正则匹配展开配置里的 `${VAR}`；当环境变量未定义时用 `""` 替换并输出 stderr 警告。 |
| **McpServerConfig** | `config/McpServerConfig.java` | MCP 服务在 YAML 内存中的结构模型，区分并存储 stdio 和 http 两种配置的字段。 |
| **McpTransport** | `mcp/transport/McpTransport.java` | 协议传输层通用接口，声明发送请求、发送通知和关闭的方法。 |
| **HttpTransport** | `mcp/transport/HttpTransport.java` | HTTP 传输实现。强制禁用 SSE（`disableServerSentEvents(true)`），通过同步 POST 与远端交互。 |
| **StdioTransport** | `mcp/transport/StdioTransport.java` | Stdio 传输实现。运行 ProcessBuilder 拉起子进程；后台虚拟线程异步读取 stdout 并利用并发 Map 依据 JSON-RPC `id` 进行响应配对。 |
| **McpSession** | `mcp/McpSession.java` | 会话状态机管理者。处理标准的 initialize 握手、initialized 通知、列出工具及发送 tool 调用请求。 |
| **McpToolAdapter** | `mcp/McpToolAdapter.java` | 适配器。包装远端工具为本地 `Tool` 接口。处理命名正则校验、`readOnlyHint` 到 `isReadOnly` 的映射，过滤非 text 响应块并限制一次性 stderr 告警。 |
| **McpManager** | `mcp/McpManager.java` | 整体生命周期管理者。用虚拟线程并发拉起所有 Session，实施 30s 启动超时隔离和 5s JVM 退出关闭兜底限制。 |
| **Main** | `Main.java` | 系统主入口。实例化 McpManager，将工具批量注册进 Registry，并注册 JVM 关闭钩子。 |
| **McpTestRunner** | `mcp/McpTestRunner.java` | 专为本章验收设计的独立测试运行器，可全自动校验环境变量、适配规则及权限拦截分流。 |

### 5.1 McpSession 消息路由与职责分工机制

在执行具体的 `tools/call` 调用时，系统实现了清晰的三层职责分工，确保了底层通信协议的彻底解耦：

1. **`McpToolAdapter` (接口适配层 - “大模型代理”)**
   * **职责**：实现本地 `Tool` 接口。负责本地和远端工具的数据适配、过滤丢弃返回结果中的非文本内容，并向 `McpSession` 发起委派。它不需要感知任何物理发送或管道流细节。
2. **`McpSession` (会话协议控制层 - “外交官”)**
   * **职责**：
     * **协议拼装**：将方法名和参数序列化并拼装为符合 JSON-RPC 2.0 规范的 `JsonRpcRequest` 结构体。
     * **ID 序列管理**：内部维护递增的原子计数器 `idGen`。每次请求时生成唯一的自增 `id`（如 `1`，`2`，`3...`），作为与回包匹配的“唯一钥匙”。
     * **同步挂起与超时控制**：向 Transport 发起发送请求后，调用 `.get(timeoutSeconds, TimeUnit.SECONDS)`。这会挂起当前虚拟线程，直至底层的监听线程收到带相同 `id` 的回包唤醒它，或在 30 秒超时后抛出异常以切断连接。
3. **`McpTransport` (物理数据传输层 - “运输司机”)**
   * **职责**：负责底层的字节收发工作。直接读取/写入 Stdio 子进程的管道（`Process.getOutputStream()`）或调用 OkHttp 客户端发起网络请求，它是唯一与外部环境进行物理接触的模块。

---

## 六、Java 21 虚拟线程在 MCP 中的深度应用

在 BingtangCode 之前的章节中，工具的调度完全是在单平台线程中串行运行的。但在本章引入的 MCP 客户端包中，我们大量且深入地应用了 **Java 21 的虚拟线程 (Virtual Threads)**。

### 5.1 虚拟线程与传统平台线程的区别

| 特性 | 传统平台线程 (Platform Thread) | 虚拟线程 (Virtual Thread) |
| :--- | :--- | :--- |
| **底层映射** | **1 : 1** 映射到操作系统内核线程 (OS Thread) | **M : N** 映射，成千上万个虚拟线程共享几个 OS 载体线程 |
| **内存开销** | **极高**。每个线程默认分配 **1 MB** 的栈内存 | **极低**。空闲时仅占用 **几百字节**（存在 Java 堆内存中） |
| **创建代价** | **昂贵**。需要向操作系统申请，涉及内核态切换 | **廉价**。纯 JVM 内存对象创建，速度极快 |
| **并发上限** | 受操作系统限制，通常达到几千个系统就会发生内存耗尽与严重卡顿 | 轻松创建**几十万、甚至上百万个**而毫无压力 |
| **阻塞代价** | 线程被阻塞时，昂贵的系统内核线程被挂起，白白浪费 CPU 算力 | 线程被阻塞时，自动把物理线程出让给其他任务，不浪费系统资源 |

### 5.2 虚拟线程的 Mount/Unmount 调度机制

虚拟线程能够以同步阻塞的代码结构跑出异步高性能的秘诀，在于 JVM 底层的挂载（Mount）与卸载（Unmount）调度：
- **挂载 (Mount)**：当一个虚拟线程被调度执行时，JVM 会将其堆栈信息“挂载”到一个操作系统的内核线程（称为载体线程 Carrier Thread）上开始运行。
- **卸载 (Unmount)**：当虚拟线程遇到了 I/O 阻塞操作（如 StdioTransport 执行 `reader.readLine()` 挂起等待输入，或 HttpTransport 使用 OkHttp 发送同步 POST 请求），JVM 会捕获这一事件，将当前虚拟线程的栈数据“搬出”并保存到 Java 堆内存中，使底层的 OS 内核线程立刻空闲下来去处理其他任务。
- **重新唤醒**：一旦阻塞的 I/O 数据到达，操作系统发出通知，JVM 会将之前暂存在堆内存里的虚拟线程重新“挂载”到一个空闲的 Carrier Thread 上，继续向下执行。

### 5.3 为什么虚拟线程是 MCP 客户端的绝配

1. **并发初始化不拖慢启动 (F9)**：  
   在启动进入对话界面前，程序必须并发对所有配置的 MCP Server（可能有十几个）发起网络连接和管道初始化。使用虚拟线程线程池 `Executors.newVirtualThreadPerTaskExecutor()`，所有 Server 的握手同时进行。即使其中有几个 Server 网络极慢，整个 BingtangCode 的启动总时延也只受最慢的那个 Server 的 30s 超时上限约束，绝不会产生串行等待的体验灾难。
2. **轻量级的后台守护线程 (StdioTransport)**：  
   对于每一个 stdio 类型的子进程，我们都必须开辟死循环去实时监听其 stdout（读回包）和 stderr（输出日志）。虚拟线程每个只占用几百字节，我们可以放心地为每个 Server 启动多个监听线程，而不需要担心浪费系统物理内存和物理线程。
3. **极简的高并发网络请求心智 (HttpTransport)**：  
   因为虚拟线程不惧怕阻塞，我们在 HTTP 传输中不需要写任何复杂的响应式回调（如 `Mono/Flux`），直接采用标准的、直观的 `OkHttp.execute()` 阻塞调用，就能并发吞吐成千上万个请求，兼顾了代码的可读性与极高的执行效能。

### 5.4 协同分工：项目中各类线程池的分类使用

在整个 `bingtangCode` 中，除了为 MCP 引入了 Java 21 虚拟线程以应对高并发阻塞 I/O 之外，其他业务模块依然根据其特定需求协同使用了传统的线程池。各类线程池分工清晰：

1. **虚拟线程及虚拟线程池 (Virtual Threads)**
   * **创建方式**：`Executors.newVirtualThreadPerTaskExecutor()` 和 `Thread.startVirtualThread()`
   * **应用场景**：MCP 服务的并发初始化与握手（[McpManager.java](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/mcp/McpManager.java)）、常驻的 Stdio 管道和 HTTP 消息流实时监听（`StdioTransport` 与 `HttpTransport`）。
   * **设计考量**：这些场景通常伴随长周期的 I/O 阻塞或网络等待，虚拟线程可挂起出让 Carrier 线程，从而以接近零成本实现极佳的高并发性能。

2. **单线程化线程池 (Single Thread Executor)**
   * **创建方式**：`Executors.newSingleThreadExecutor(ThreadFactory)`
   * **应用场景**：本地工具执行与超时控制（`ToolExecutor`）、大模型请求后台排队与取消（`AnthropicProvider` / `OpenAIProvider`）、终端权限弹窗确认（`TerminalIO`）。
   * **设计考量**：这些操作具有**强顺序性要求**（例如同一时间只允许运行一个修改文件的工具以防冲突），或者需要通过 `Future.get(timeout)` 实施精准的单任务超时控制，因此传统的单平台线程池是最佳设计选择。

3. **缓存线程池 (Cached Thread Pool)**
   * **创建方式**：`Executors.newCachedThreadPool(ThreadFactory)`
   * **应用场景**：多轮对话批量计算任务（`DialogueManager`）。
   * **设计考量**：用于短生命周期的异步批处理任务，根据负载动态伸缩。

---

## 七、重要注意事项与设计细节

### 1. 故障隔离与启动非阻塞 (N1)
单个 Server 的错误被限制在它自身范围。
如果某个 Server 连接失败、握手超时（超过 30s）、启动命令填错（缺少二进制）、或者 HTTP url 404，`McpManager` 会在 stderr 输出告警信息，然后**直接跳过它**。这可以保证 BingtangCode 能够正常进入 TUI 对话界面，其他健康的工具集（包括本地内置 6 个工具）依然能够正常提供服务。

### 2. 只读特性的保守信任 (N2/F7)
在 `McpToolAdapter` 中，只有当远端定义里包含 `"readOnlyHint": true` 的标记时，本地的 `isReadOnly()` 才会返回 `true`。如果字段缺失、格式非法或者为 `false`，则统一兜底为 `false`。
因为没有被白纸黑字写明“只读”的工具，一律被当成“写操作（具有副作用）”处理。在默认的权限模式下，这会被拦截并强制要求用户手动 `[y/N]` 确认，保证了“安全默认（Secure by Default）”。

### 3. 非文本响应块过滤告警
MCP 规范允许工具返回图像、音频等二进制内容，但 BingtangCode 的 AI 助手底层只能处理文本输入。为了避免向 LLM 回灌格式错乱的数据导致异常，适配层会自动过滤丢弃非 text 内容。同时，为了防止丢弃的行为让开发人员一无所知，工具在**首次/每次**执行丢弃时，会在控制台 stderr 打印一次性警告，避免警告轰炸。

### 4. 两层配置的完全覆盖机制 (F1)
为了避免用户级和项目级配置在字段合并时产生脏配置，BingtangCode 对同名 Server 采取**整对象覆盖**的逻辑（项目级覆盖用户级）。例如，在项目级声明了 `weather`，那么用户级里 `weather` 的 `env` 或 `headers` 会被完整弃用，转而完全采纳项目级配置。

### 5. 权限系统的无感对接与绕过说明 (F12)
由于 MCP 工具不属于内置工具：
- 它在 `PermissionGate.check()` 时，第一层（黑名单）和第二层（路径沙箱）会**自动无感跳过（豁免）**，因为它们不匹配任何内置的 `isFileTool` 和 `isBash` 判定条件。这与标准规范保持一致（外部进程沙箱职责由服务端子进程负责）。
- 但它们在 **Layer 3 (规则引擎)** 中完全受控（可写 `mcp__*` 的规则拦截），在 **Layer 4/5 (模式兜底与人在回路)** 中由于只读/读写属性被安全分流（只读放行，写操作强制询问）。整个权限控制做到了**权限包源码零修改**，完全复用了已有逻辑。

### 6. Stdio 与 HTTP 传输模式的选用场景与决策
客户端同时支持 `stdio` and `http` 两种标准传输协议，在集成第三方工具时根据以下原则进行选用决策：

*   **选用 `stdio` 模式（本地进程托管）**：
    *   **适用场景**：工具服务安装在本机（如 npm 命令行工具包、Python 脚本或本地编译好的二进制文件）。你希望 `bingtangcode` 启动时自动运行它，退出时自动关闭它。
    *   **优势**：免配置（无需指定 IP 端口）、防冲突（不占本地网络端口）、低开销（进程间内存管道直连吞吐极快）、安全性高（外部局域网完全无法访问或窥探该子进程管道）。
    *   **典型配置**：`npx -y @upstash/context7-mcp` 或 `python my_tool.py`。
*   **选用 `http` 模式（网络共享连接）**：
    *   **适用场景**：工具服务运行在远程云端（如公司公共的文档搜索引擎 API）或是一个已经由其他守护进程提前开启并常驻于本地特定端口的 Web 服务。
    *   **优势**：集中共享（团队可共用同一个云端接口服务），客户端无需在本地配置 Node.js/Python 运行环境，只需提供访问 URL 即可开箱即用。
    *   **典型配置**：`https://mcp.mycompany.com/api`。
