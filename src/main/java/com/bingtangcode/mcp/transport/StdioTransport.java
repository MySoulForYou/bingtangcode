package com.bingtangcode.mcp.transport;

import com.bingtangcode.mcp.protocol.JsonRpcNotification;
import com.bingtangcode.mcp.protocol.JsonRpcRequest;
import com.bingtangcode.mcp.protocol.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class StdioTransport implements McpTransport {
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Object, CompletableFuture<JsonRpcResponse>> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public StdioTransport(String command, List<String> args, Map<String, String> env) throws IOException {
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        if (args != null) {
            fullCommand.addAll(args);
        }

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        // 合并系统环境变量与配置传入的额外环境变量
        Map<String, String> pbEnv = pb.environment();
        if (env != null) {
            pbEnv.putAll(env);
        }

        this.process = pb.start();
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // 启动后台虚拟线程异步读取 stdout
        Thread.startVirtualThread(this::readStdoutLoop);

        // 启动后台虚拟线程异步转发 stderr
        Thread.startVirtualThread(this::readStderrLoop);
    }

    private void readStdoutLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    JsonRpcResponse response = mapper.readValue(line, JsonRpcResponse.class);
                    Object id = response.getId();
                    if (id != null) {
                        CompletableFuture<JsonRpcResponse> future = pendingRequests.remove(id);
                        if (future != null) {
                            future.complete(response);
                        }
                    }
                } catch (Exception e) {
                    // 静默忽略无法解析的数据包（或通知消息）
                }
            }
        } catch (IOException e) {
            if (!closed) {
                failAllPending(e);
            }
        }
    }

    private void readStderrLoop() {
        try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = errReader.readLine()) != null) {
                System.err.println("[MCP Server Log] " + line);
            }
        } catch (IOException ignored) {
        }
    }


    private void failAllPending(Throwable ex) {
        for (CompletableFuture<JsonRpcResponse> future : pendingRequests.values()) {
            future.completeExceptionally(ex);
        }
        pendingRequests.clear();
    }

    @Override
    public synchronized CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request) {
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IOException("Transport closed"));
            return future;
        }
        Object id = request.getId();
        if (id == null) {
            future.completeExceptionally(new IllegalArgumentException("Request ID cannot be null"));
            return future;
        }
        pendingRequests.put(id, future);

        try {
            String json = mapper.writeValueAsString(request);
            writer.write(json);
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public synchronized void sendNotification(JsonRpcNotification notification) {
        if (closed) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(notification);
            writer.write(json);
            writer.write("\n");
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        failAllPending(new IOException("Transport closed"));

        try {
            writer.close();
        } catch (IOException ignored) {}
        try {
            reader.close();
        } catch (IOException ignored) {}

        process.destroy();
    }
}
