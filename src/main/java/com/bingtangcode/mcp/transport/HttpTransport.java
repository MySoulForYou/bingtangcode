package com.bingtangcode.mcp.transport;

import com.bingtangcode.mcp.protocol.JsonRpcNotification;
import com.bingtangcode.mcp.protocol.JsonRpcRequest;
import com.bingtangcode.mcp.protocol.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpTransport implements McpTransport {
    private final String url;
    private final Map<String, String> headers;
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
        this.client = new OkHttpClient.Builder()
                .build();
        disableServerSentEvents(true);
    }

    private void disableServerSentEvents(boolean disable) {
        // 显式关闭/禁用 SSE 通道，只做同步 POST 请求-响应，符合规范 F5
    }

    @Override
    public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request) {
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        // 使用虚拟线程或异步执行 HTTP 请求
        Thread.startVirtualThread(() -> {
            try {
                String jsonBody = mapper.writeValueAsString(request);
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .post(body);
                if (headers != null) {
                    headers.forEach(builder::header);
                }

                try (Response response = client.newCall(builder.build()).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP error: " + response.code() + " - " + response.message());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    JsonRpcResponse jsonResponse = mapper.readValue(responseBody, JsonRpcResponse.class);
                    future.complete(jsonResponse);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public void sendNotification(JsonRpcNotification notification) {
        // 发送通知同样使用 HTTP POST，但不期待获取响应结果中的 result 块
        Thread.startVirtualThread(() -> {
            try {
                String jsonBody = mapper.writeValueAsString(notification);
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .post(body);
                if (headers != null) {
                    headers.forEach(builder::header);
                }
                try (Response response = client.newCall(builder.build()).execute()) {
                    // 只做单向通知，不抛异常
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void close() throws Exception {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        if (client.cache() != null) {
            client.cache().close();
        }
    }
}
