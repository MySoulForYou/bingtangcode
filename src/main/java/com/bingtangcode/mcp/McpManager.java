package com.bingtangcode.mcp;

import com.bingtangcode.config.ConfigManager;
import com.bingtangcode.config.McpServerConfig;
import com.bingtangcode.mcp.transport.HttpTransport;
import com.bingtangcode.mcp.transport.McpTransport;
import com.bingtangcode.mcp.transport.StdioTransport;
import com.bingtangcode.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;

public class McpManager implements AutoCloseable {
    private final List<McpSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Tool> tools = new CopyOnWriteArrayList<>();

    private McpManager() {}

    /**
     * 并发启动和连接所有配置的 MCP Server，每个 Server 最大超时为 30 秒。
     * 采用故障隔离机制，单个 Server 初始化失败不阻断程序启动。
     */
    public static McpManager start(ConfigManager config) {
        McpManager manager = new McpManager();
        Map<String, McpServerConfig> serversMap = config.getMcpServers();
        if (serversMap == null || serversMap.isEmpty()) {
            return manager;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<Tool> tempTools = new CopyOnWriteArrayList<>();
        ExecutorService connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();

        for (Map.Entry<String, McpServerConfig> entry : serversMap.entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig serverConfig = entry.getValue();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                McpSession session = null;
                try {
                    McpTransport transport;
                    if ("stdio".equals(serverConfig.getType())) {
                        transport = new StdioTransport(
                                serverConfig.getCommand(),
                                serverConfig.getArgs(),
                                serverConfig.getEnv()
                        );
                    } else if ("http".equals(serverConfig.getType())) {
                        transport = new HttpTransport(
                                serverConfig.getUrl(),
                                serverConfig.getHeaders()
                        );
                    } else {
                        // 应该已经在配置校验层被过滤掉了，作为兜底直接返回
                        return;
                    }

                    session = new McpSession(serverName, transport);
                    // 每一个 Server 初始化最大超时限制
                    session.initialize(config.getMcpTimeoutSeconds());

                    // 成功连接后适配并提取工具
                    for (JsonNode toolNode : session.getTools()) {
                        McpToolAdapter adapter = McpToolAdapter.create(serverName, toolNode, session, config.getMcpTimeoutSeconds());
                        if (adapter != null) {
                            tempTools.add(adapter);
                        }
                    }

                    manager.sessions.add(session);
                } catch (Exception e) {
                    System.err.println("[警告] 无法初始化 MCP Server " + serverName + ": " + e.getMessage());
                    if (session != null) {
                        try {
                            session.close();
                        } catch (Exception ignored) {}
                    }
                }
            }, connectionExecutor);

            // 限制单个 Server 最长超时时间
            futures.add(future.orTimeout(config.getMcpTimeoutSeconds(), TimeUnit.SECONDS));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception ignored) {
            // Join 中捕获超时或其它异常，此时故障隔离已生效
        } finally {
            connectionExecutor.shutdown();
        }

        // 对所有成功注册的工具按照工具全名排序，保证 Agent 看到的顺序一致
        List<Tool> sortedTools = new ArrayList<>(tempTools);
        sortedTools.sort(Comparator.comparing(Tool::getName));
        manager.tools.addAll(sortedTools);

        return manager;
    }

    public List<Tool> getTools() {
        return tools;
    }

    @Override
    public void close() throws Exception {
        if (sessions.isEmpty()) {
            return;
        }

        ExecutorService closeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        for (McpSession session : sessions) {
            closeExecutor.submit(() -> {
                try {
                    session.close();
                } catch (Exception ignored) {}
            });
        }

        closeExecutor.shutdown();
        try {
            // JVM 退出销毁子进程兜底，总超时等待 5 秒，防止关闭卡死
            if (!closeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                closeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            closeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
