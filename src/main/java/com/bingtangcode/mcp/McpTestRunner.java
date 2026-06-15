package com.bingtangcode.mcp;

import com.bingtangcode.config.ConfigManager;
import com.bingtangcode.config.EnvExpander;
import com.bingtangcode.config.McpServerConfig;
import com.bingtangcode.mcp.protocol.JsonRpcNotification;
import com.bingtangcode.mcp.protocol.JsonRpcRequest;
import com.bingtangcode.mcp.protocol.JsonRpcResponse;
import com.bingtangcode.mcp.transport.McpTransport;
import com.bingtangcode.permission.*;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolRegistry;
import com.bingtangcode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class McpTestRunner {

    public static void main(String[] args) {
        System.out.println("====== MCP 客户端集成自动化测试 ======");
        boolean allPassed = true;

        allPassed &= testEnvExpander();
        allPassed &= testMcpToolAdapter();
        allPassed &= testPermissionGateBypassAndRouting();

        System.out.println("\n=================================");
        if (allPassed) {
            System.out.println(">>> 所有 MCP 核心校验通过！ <<<");
        } else {
            System.out.println(">>> 部分 MCP 校验失败，请检查！ <<<");
            System.exit(1);
        }
    }

    private static boolean testEnvExpander() {
        System.out.println("\n--- 测试 1: 环境变量展开器 (EnvExpander) ---");
        try {
            // 测试已定义变量
            String user = System.getenv("USER");
            if (user == null || user.isEmpty()) {
                user = "laq"; // fallback for test environments
            }
            String expandedUser = EnvExpander.expand("Hello ${USER}");
            System.out.println("展开 ${USER}: " + expandedUser);
            if (!expandedUser.contains(user)) {
                System.err.println("FAIL: 展开已定义变量失败");
                return false;
            }

            // 测试未定义变量 & 收集警告
            ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(errCapture));
            
            String undefinedResult;
            try {
                undefinedResult = EnvExpander.expand("Test ${NON_EXISTENT_VAR_ABC_123}");
            } finally {
                System.setErr(originalErr);
            }

            String warnings = errCapture.toString();
            System.out.println("展开未定义变量结果: \"" + undefinedResult + "\"");
            System.out.print("捕获的警告: " + warnings);

            if (!"Test ".equals(undefinedResult)) {
                System.err.println("FAIL: 未定义变量应该展开为空字符串");
                return false;
            }
            if (!warnings.contains("NON_EXISTENT_VAR_ABC_123") || !warnings.contains("未定义，展开为空字符串")) {
                System.err.println("FAIL: 警告日志格式不匹配");
                return false;
            }

            System.out.println("PASS: 环境变量展开器验证成功");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testMcpToolAdapter() {
        System.out.println("\n--- 测试 2: 工具适配器 (McpToolAdapter) ---");
        try {
            ObjectMapper mapper = new ObjectMapper();

            // 1. 测试合法与非法工具命名
            JsonNode validNode = mapper.readTree("{\"name\":\"get_weather\",\"description\":\"Read weather\",\"inputSchema\":{\"type\":\"object\"}}");
            McpToolAdapter validAdapter = McpToolAdapter.create("weather", validNode, null);
            if (validAdapter == null || !"mcp__weather__get_weather".equals(validAdapter.getName())) {
                System.err.println("FAIL: 合法工具适配失败");
                return false;
            }

            ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(errCapture));
            McpToolAdapter invalidAdapter;
            try {
                JsonNode invalidNode = mapper.readTree("{\"name\":\"get$forecast\",\"description\":\"Read forecast\",\"inputSchema\":{\"type\":\"object\"}}");
                invalidAdapter = McpToolAdapter.create("weather", invalidNode, null);
            } finally {
                System.setErr(originalErr);
            }
            System.out.print("捕获的命名校验告警: " + errCapture.toString());
            if (invalidAdapter != null) {
                System.err.println("FAIL: 非法工具名应被跳过注册");
                return false;
            }
            if (!errCapture.toString().contains("get$forecast") || !errCapture.toString().contains("含有非法字符")) {
                System.err.println("FAIL: 非法命名告警格式不匹配");
                return false;
            }

            // 2. 测试 readOnlyHint 映射
            JsonNode roNode = mapper.readTree("{\"name\":\"get_weather\",\"description\":\"Read weather\",\"annotations\":{\"readOnlyHint\":true}}");
            McpToolAdapter roAdapter = McpToolAdapter.create("weather", roNode, null);
            if (roAdapter == null || !roAdapter.isReadOnly()) {
                System.err.println("FAIL: readOnlyHint 应该映射为 isReadOnly() == true");
                return false;
            }

            JsonNode wrNode = mapper.readTree("{\"name\":\"write_weather\",\"description\":\"Write weather\",\"annotations\":{\"readOnlyHint\":false}}");
            McpToolAdapter wrAdapter = McpToolAdapter.create("weather", wrNode, null);
            if (wrAdapter == null || wrAdapter.isReadOnly()) {
                System.err.println("FAIL: readOnlyHint == false 应该映射为 isReadOnly() == false");
                return false;
            }

            // 3. 测试非文本数据块静默丢弃与一次性告警
            McpTransport dummyTransport = new McpTransport() {
                @Override
                public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request) {
                    JsonRpcResponse response = new JsonRpcResponse();
                    try {
                        // 返回一个文本块与一个图像内容块
                        String resultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"Weather is sunny.\"},{\"type\":\"image\",\"data\":\"binary-data\"}],\"isError\":false}";
                        response.setResult(mapper.readTree(resultJson));
                    } catch (Exception ignored) {}
                    return CompletableFuture.completedFuture(response);
                }
                @Override
                public void sendNotification(JsonRpcNotification notification) {}
                @Override
                public void close() {}
            };
            McpSession session = new McpSession("weather", dummyTransport);
            JsonNode testToolNode = mapper.readTree("{\"name\":\"read_weather\",\"description\":\"weather\"}");
            McpToolAdapter executeAdapter = McpToolAdapter.create("weather", testToolNode, session);

            errCapture.reset();
            System.setErr(new PrintStream(errCapture));
            ToolResult result1;
            ToolResult result2;
            try {
                result1 = executeAdapter.execute(Map.of());
                result2 = executeAdapter.execute(Map.of());
            } finally {
                System.setErr(originalErr);
            }

            System.out.println("第一次调用结果 content: \"" + result1.content() + "\"");
            System.out.print("调用捕获的非文本丢弃警告:\n" + errCapture.toString());

            if (!"Weather is sunny.".equals(result1.content())) {
                System.err.println("FAIL: 非文本数据块应被静默丢弃");
                return false;
            }

            // 验证警告是一次性的（两条调用只输出了一条告警）
            String warnings2 = errCapture.toString();
            int count = 0;
            int idx = 0;
            while ((idx = warnings2.indexOf("已被静默丢弃", idx)) != -1) {
                count++;
                idx += "已被静默丢弃".length();
            }
            if (count != 1) {
                System.err.println("FAIL: 告警应当是一次性的，期望 1 次但实际输出 " + count + " 次");
                return false;
            }

            System.out.println("PASS: 工具适配器特性校验成功");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testPermissionGateBypassAndRouting() {
        System.out.println("\n--- 测试 3: 权限门禁绕过与分流校验 (PermissionGate) ---");
        try {
            ObjectMapper mapper = new ObjectMapper();

            // 1. 初始化 ToolRegistry 与 Mock 工具
            ToolRegistry registry = new ToolRegistry();
            // 注册一个 MCP 只读工具
            JsonNode roNode = mapper.readTree("{\"name\":\"read_db\",\"annotations\":{\"readOnlyHint\":true}}");
            McpToolAdapter roAdapter = McpToolAdapter.create("db", roNode, null);
            registry.register(roAdapter);

            // 注册一个 MCP 副作用工具
            JsonNode wrNode = mapper.readTree("{\"name\":\"write_db\",\"annotations\":{\"readOnlyHint\":false}}");
            McpToolAdapter wrAdapter = McpToolAdapter.create("db", wrNode, null);
            registry.register(wrAdapter);

            // 2. 初始化 Mock Permission components
            Path root = Path.of("").toAbsolutePath();
            PermissionConfigLoader loader = new PermissionConfigLoader(root) {
                @Override
                public List<PermissionRule> loadUserRules() { return List.of(); }
                @Override
                public List<PermissionRule> loadProjectRules() { return List.of(); }
                @Override
                public List<PermissionRule> loadLocalRules() { return List.of(); }
            };
            PermissionConfigManager manager = new PermissionConfigManager(root, loader);
            RuleEngine ruleEngine = new RuleEngine(loader);
            Blacklist blacklist = new Blacklist();
            
            // Mock HumanInTheLoopHandler that denies so we can see if write tool goes to Ask
            HumanInTheLoopHandler hitl = (tc, m) -> AskResult.DENY_ONCE;

            PermissionGate gate = new PermissionGate(
                    ruleEngine, blacklist, () -> PermissionMode.DEFAULT, hitl, manager, registry, root);
            gate.setMode(PermissionMode.DEFAULT);

            // 3. 验证只读工具 (isReadOnly == true)
            // 在 DEFAULT 模式下，只读工具应该在 Layer 4 自动放行 (ALLOW)，不触发 Layer 5 Ask
            ToolCall roCall = new ToolCall("call1", "mcp__db__read_db", new HashMap<>());
            PermissionResult roResult = gate.check(roCall);
            System.out.println("只读工具权限结果: ALLOW=" + roResult.allowed() + ", Reason=" + roResult.reason());
            if (!roResult.allowed()) {
                System.err.println("FAIL: 只读工具在 DEFAULT 模式下应被自动允许");
                return false;
            }

            // 4. 验证副作用工具 (isReadOnly == false)
            // 在 DEFAULT 模式下，副作用工具应该被 Layer 4 拒绝并进入 Layer 5 Ask 从而被 DENY_ONCE
            ToolCall wrCall = new ToolCall("call2", "mcp__db__write_db", new HashMap<>());
            PermissionResult wrResult = gate.check(wrCall);
            System.out.println("副作用工具权限结果: ALLOW=" + wrResult.allowed() + ", Reason=" + wrResult.reason() + ", MatchedRule=" + wrResult.matchedRule());
            if (wrResult.allowed() || !"human-in-the-loop".equals(wrResult.matchedRule())) {
                System.err.println("FAIL: 副作用工具应该进入人在回路拦截");
                return false;
            }

            System.out.println("PASS: 权限门禁绕过与只读分流校验成功");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
