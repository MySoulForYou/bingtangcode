package com.bingtangcode.mcp;

import com.bingtangcode.mcp.protocol.JsonRpcNotification;
import com.bingtangcode.mcp.protocol.JsonRpcRequest;
import com.bingtangcode.mcp.protocol.JsonRpcResponse;
import com.bingtangcode.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class McpSession implements AutoCloseable {
    private final String serverName;
    private final McpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger idGen = new AtomicInteger(1);
    private final List<JsonNode> tools = new ArrayList<>();

    public McpSession(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    /**
     * 执行连接握手与初始化流程：
     * 1. 发送 initialize 请求（协议版本 "2025-06-18"）
     * 2. 发送 notifications/initialized 通知
     * 3. 调用 tools/list 列出工具并存储
     */
    public void initialize(long timeoutSeconds) throws Exception {
        // 1. initialize 请求
        int initId = idGen.getAndIncrement();
        ObjectNode initParams = mapper.createObjectNode();
        initParams.put("protocolVersion", "2025-06-18");
        initParams.set("capabilities", mapper.createObjectNode());
        
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "bingtangCode");
        clientInfo.put("version", "0.5.0");
        initParams.set("clientInfo", clientInfo);

        JsonRpcRequest initRequest = new JsonRpcRequest(initId, "initialize", initParams);
        JsonRpcResponse initResponse = transport.sendRequest(initRequest)
                .get(timeoutSeconds, TimeUnit.SECONDS);

        if (initResponse.getError() != null) {
            throw new IOException("Initialize error from server: " + initResponse.getError().getMessage());
        }

        // 2. notifications/initialized 通知
        JsonRpcNotification initializedNotification = new JsonRpcNotification(
                "notifications/initialized", mapper.createObjectNode());
        transport.sendNotification(initializedNotification);

        // 3. tools/list 请求获取工具列表
        int listId = idGen.getAndIncrement();
        JsonRpcRequest listRequest = new JsonRpcRequest(listId, "tools/list", mapper.createObjectNode());
        JsonRpcResponse listResponse = transport.sendRequest(listRequest)
                .get(timeoutSeconds, TimeUnit.SECONDS);

        if (listResponse.getError() != null) {
            throw new IOException("List tools error from server: " + listResponse.getError().getMessage());
        }

        JsonNode result = listResponse.getResult();
        if (result != null && result.has("tools")) {
            JsonNode toolsNode = result.get("tools");
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    tools.add(toolNode);
                }
            }
        }
    }

    /**
     * 发送 tools/call 请求调用远端工具
     */
    public JsonNode callTool(String toolName, Map<String, Object> arguments, long timeoutSeconds) throws Exception {
        int callId = idGen.getAndIncrement();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(arguments));

        JsonRpcRequest request = new JsonRpcRequest(callId, "tools/call", params);
        JsonRpcResponse response = transport.sendRequest(request)
                .get(timeoutSeconds, TimeUnit.SECONDS);

        if (response.getError() != null) {
            throw new IOException("MCP Server Error (" + response.getError().getCode() + "): " + response.getError().getMessage());
        }

        return response.getResult();
    }

    public String getServerName() {
        return serverName;
    }

    public List<JsonNode> getTools() {
        return tools;
    }

    @Override
    public void close() throws Exception {
        transport.close();
    }
}
