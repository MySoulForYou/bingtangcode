# MCP 客户端开发任务拆解 (Tasks)

## 任务 1: 环境变量解析器
- **目标**：实现一个工具类用于展开字符串中的 `${VAR}` 占位符（读取 `System.getenv("VAR")`）。若变量未定义，则替换为空字符串并在 `System.err` 中打印格式为 `[警告] 环境变量 VAR 未定义，展开为空字符串` 的告警，且不阻断启动。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/config/EnvExpander.java`
- **依赖任务**：无

## 任务 2: 配置加载、合并与合法性校验
- **目标**：定义 Stdio 和 HTTP 类型的 MCP 服务配置数据模型。更新 `ConfigManager`，使其支持读取 `~/.bingtangcode/config.yaml`（用户级）和当前目录下的 `config.yaml`（项目级）配置，执行合并（项目级覆盖用户级，不作字段级合并），对所有 Server 配置应用环境变量展开，并解析为 `mcp_servers` 映射。
  在解析时进行合法性校验：
  - `type` 必须为 `stdio` 或 `http`（不区分大小写）。
  - 若为 `stdio`，必须包含 `command`；若为 `http`，必须包含 `url`。
  - 校验失败的 Server，直接打印 `System.err` 告警并跳过该 Server 的初始化，不影响其他 Server。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/config/McpServerConfig.java`
  - `[MODIFY] src/main/java/com/bingtangcode/config/ConfigManager.java` (参考 [ConfigManager.java:L36](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/config/ConfigManager.java#L36-L81))
- **依赖任务**：任务 1

## 任务 3: JSON-RPC 消息实体建模
- **目标**：创建类以表示 JSON-RPC 2.0 规范中的 Request、Response 和 Notification 结构，便于与 MCP 服务进行 JSON 序列化与反序列化。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/mcp/protocol/JsonRpcRequest.java`
  - `[NEW] src/main/java/com/bingtangcode/mcp/protocol/JsonRpcResponse.java`
  - `[NEW] src/main/java/com/bingtangcode/mcp/protocol/JsonRpcNotification.java`
- **依赖任务**：无

## 任务 4: 传输接口与 HTTP 传输实现（显式禁用 SSE）
- **目标**：定义通用传输接口 `McpTransport`。实现 `HttpTransport`（使用 OkHttp），只通过同步 HTTP POST 请求发送 JSON-RPC 消息并直接读取响应。在构造时需要配置或实现 `disableServerSentEvents(true)` 的配置方法，明确只做单次请求-响应，不订阅 SSE 长连接流。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/mcp/transport/McpTransport.java`
  - `[NEW] src/main/java/com/bingtangcode/mcp/transport/HttpTransport.java`
- **依赖任务**：任务 3

## 任务 5: Stdio 传输实现与多线程异步响应配对
- **目标**：实现 `StdioTransport`。使用 `ProcessBuilder` 启动子进程，运行后台线程异步读取子进程的 `stdout`（按行解析 JSON-RPC），通过线程安全的并发 Map 与 `CompletableFuture` 按消息 ID 异步配对请求与响应，同时将子进程的 `stderr` 输出到控制台。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/mcp/transport/StdioTransport.java`
- **依赖任务**：任务 3

## 任务 6: 会话管理器与 MCP 握手实现 (使用最新版协议)
- **目标**：创建 `McpSession` 管理与 Server 的握手流程。包括发送 `initialize` 请求（协议版本为 SDK 最新版如 `"2025-06-18"`）、发送 `notifications/initialized` 通知、调用 `tools/list` 获取远端工具列表，并提供关闭连接的接口。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/mcp/McpSession.java`
- **依赖任务**：任务 4, 任务 5

## 任务 7: 工具适配层与特性处理
- **目标**：编写 `McpToolAdapter` 以实现 BingtangCode 原生的 `Tool` 接口。
  进行如下适配与校验：
  - **前缀与格式校验**：工具全名 `mcp__<server>__<tool>` 必须符合正则 `^[A-Za-z0-9_-]+$`。如果不匹配则跳过该工具注册，并打印 `System.err` 告警。
  - **只读适配**：当工具的 `annotations.readOnlyHint` 为 `true` 时，`isReadOnly()` 方法返回 `true`，否则返回 `false`。
  - **非文本内容丢弃**：工具执行时，丢弃非文本（non-text）类型的内容块。对于该工具，如果发生此类丢弃，则在首次遇到时向 `System.err` 输出一次性警告。
- **影响文件**：
  - `[NEW] src/main/java/com/bingtangcode/mcp/McpToolAdapter.java`
- **依赖任务**：任务 6

## 任务 8: 虚拟线程并发接入与退出钩子
- **目标**：修改 `Main.java`，加载 MCP 配置。使用 Java 21 虚拟线程线程池 (`Executors.newVirtualThreadPerTaskExecutor()`) 并发连接与启动所有 active 的 `McpSession`。
  - 每一个 Server 初始化最大超时为 30s。
  - 成功初始化的工具注册进 `ToolRegistry`。
  - 注册 JVM Shutdown Hook 兜底清理所有 Stdio 子进程，设置最大 5s 的安全关闭等待时间。
- **影响文件**：
  - `[MODIFY] src/main/java/com/bingtangcode/Main.java` (参考 [Main.java:L126](file:///Users/laq/Documents/bingtangcode/src/main/java/com/bingtangcode/Main.java#L126-L135))
- **依赖任务**：任务 7

## 任务 9: 权限链路集成与校验（零源码修改验证）
- **目标**：验证注册的以 `mcp__` 开头的工具自动经过权限门禁的规则校验。
  - 验证其能够被 `mcp__*` 形式的 allow / deny 规则匹配控制。
  - 验证其调用时：黑名单与路径沙箱判定被自动跳过（因为 `extractTarget` 返回空值或 `isFile=false`）。
  - 验证 `isReadOnly() == true` 的只读工具被放行，非只读工具被拦截触发 Ask。
- **影响文件**：无（确保权限包源码零修改）
- **依赖任务**：任务 8

## 任务 10: 端到端联调测试
- **目标**：使用包含环境变量、违法字符工具名、非文本数据块的 mock 协议服务测试，并运行端到端流程验证在 30s 超时和 5s 强制关闭下的客户端表现。
- **影响文件**：无
- **依赖任务**：任务 9
