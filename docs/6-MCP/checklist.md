# MCP 客户端集成验收清单 (Checklist)

## 1. 环境变量解析器验证
- [ ] 调用环境变量替换，解析 `"Hello ${USER}"` 应该输出 `"Hello <当前系统用户名>"`（与系统中 `System.getenv("USER")` 的实际值一致）。
- [ ] 调用环境变量替换，解析 `"${NON_EXISTENT_VAR_ABC_123}"` 应该输出 `""`（空字符串），同时在 `System.err` 输出一条告警日志，包含 `[警告] 环境变量 NON_EXISTENT_VAR_ABC_123 未定义` 字样。

## 2. 配置加载与合法性校验验证
- [ ] 在 `config.yaml` 中增加以下测试用例并启动程序：
  ```yaml
  mcp_servers:
    invalid_stdio:
      type: "stdio"
      # 故意缺失 command
    invalid_http:
      type: "http"
      # 故意缺失 url
    invalid_type:
      type: "websocket" # 故意写错 type
      url: "ws://localhost:9000"
    valid_stdio:
      type: "stdio"
      command: "echo"
      args: ["hello"]
  ```
  验收结果：
  - `System.err` 输出三条告警，分别提示 `invalid_stdio` 缺失 command、`invalid_http` 缺失 url，以及 `invalid_type` 类型非法。
  - 程序不崩溃，依然能够正常读取并加载 `valid_stdio`，说明非法配置被正确跳过，未阻断正常服务。

- [ ] 验证用户级与项目级配置合并优先级：
  - 用户级配置 `~/.bingtangcode/config.yaml` 中配置 `test_server` 指向 `http://localhost:8080/mcp`。
  - 项目级配置 `./config.yaml` 中配置 `test_server` 指向 `http://localhost:9090/mcp`。
  - 启动后，验证实际连接请求发送到了 `http://localhost:9090/mcp`，项目级同名配置整对象替换（覆盖）。

## 3. HTTP 传输禁用 SSE 验证
- [ ] 检查 `HttpTransport` 源码实现，确保包含 `disableServerSentEvents(true)` 的显式设置（或类似的配置控制），只使用同步 POST 请求-响应，不订阅任何 SSE 流。

## 4. 虚拟线程并发连接与超时校验
- [ ] 配置 3 个模拟 stdio 服务端，各服务在收到 `initialize` 请求时人为挂起延迟 3s 响应。
  - 启动 BingtangCode，记录从启动加载到全部完成的总时间。总时间应该在 `3~4s` 左右，而**绝非**串行等待的 `9s+`，以此证明使用了 Virtual Threads 进行并发连接。
- [ ] 配置一个挂起不响应（超过 30s）的模拟服务端，启动 BingtangCode，在 30s 后观察该连接任务抛出 TimeoutException 并打印 `System.err` 错误日志，且此时其他正常的 Server 已正常注册工具并启动，不被此超时 Server 阻塞。

## 5. 工具适配器命名、只读与内容过滤验证
- [ ] 模拟一个服务端，提供名为 `get-forecast`（包含非法字符 `-`）和 `read_weather`（合法字符）的工具。
  - 启动后，在 `System.err` 中能够观察到一条关于 `get-forecast` 的跳过注册告警，格式类似 `[警告] 工具名称 mcp__weather__get-forecast 含有非法字符，已跳过注册`。
  - 在 `ToolRegistry` 中仅能获取到 `mcp__weather__read_weather`，无 `mcp__weather__get-forecast`。
- [ ] 模拟一个服务端工具定义：
  - 工具 A：在 `annotations` 中配置 `"readOnlyHint": true`。
  - 工具 B：未配置或配置 `"readOnlyHint": false`。
  - 验证本地注册完成后：工具 A 的 `Tool.isReadOnly()` 返回 `true`；工具 B 的 `Tool.isReadOnly()` 返回 `false`。
- [ ] 模拟工具返回多块内容：一个文本块（text），一个图像内容块（image）。
  - 执行调用后，Agent 接收到的 `ToolResult.content()` 只包含该文本块的内容，非文本块被静默丢弃。
  - 并且 `System.err` 中仅在该工具首次/每次丢弃时输出一次性警告：`[警告] 工具 mcp__xxx 包含非文本内容块，已被静默丢弃`。

## 6. 安全退出兜底验证
- [ ] 启动 BingtangCode 注册一个 stdio 子进程。然后强制退出客户端，检查子进程生命周期。并且在销毁逻辑中通过配置或代码体现，关闭等待时间最高限额为 5 秒。

## 7. 权限链路绕过与分流集成验证
- [ ] 启动 BingtangCode 并配置 MCP 服务。对 AI 助手发起指令，使其触发调用一个 MCP 工具（如 `mcp__weather__read_weather`）。
- [ ] 验证调用是否通过 `PermissionGate`：
  - MCP 工具的权限检查中，黑名单与沙箱阶段应被自动跳过（由于不匹配对应的内置工具名，自动豁免）。
  - 权限拦截器的匹配目标应展示为 `mcp__*`。
  - 若为**只读**工具（`isReadOnly() == true`），在只读安全模式下应该被自动允许并直接执行。
  - 若为**副作用/写**工具（`isReadOnly() == false`），则应正确触发弹框或控制台询问：`是否允许执行 mcp__* 工具？`。
