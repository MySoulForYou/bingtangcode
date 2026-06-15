package com.bingtangcode.mcp.transport;

import com.bingtangcode.mcp.protocol.JsonRpcNotification;
import com.bingtangcode.mcp.protocol.JsonRpcRequest;
import com.bingtangcode.mcp.protocol.JsonRpcResponse;
import java.util.concurrent.CompletableFuture;

public interface McpTransport extends AutoCloseable {
    CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request);
    void sendNotification(JsonRpcNotification notification);
}
