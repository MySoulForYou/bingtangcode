package com.bingtangcode.llm.LLMimpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolCall;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnthropicProvider implements LLMProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final int maxTokens;
    private volatile boolean cancelled;
    private volatile Response activeResponse;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AnthropicProvider(String apiKey, String model, String endpoint, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
        this.maxTokens = maxTokens;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "anthropic-stream-reader");
            t.setDaemon(true);
            return t;
        });
    }

    public void cancel() {
        cancelled = true;
        Response r = activeResponse;
        if (r != null) {
            r.close();
        }
    }

    /**
     * 异步发起流式请求。
     * 提交到单线程 executor 后立即返回——SSE 解析和回调都在 provider 线程上执行。
     * 这样 SessionManager 主线程可以 latch.await() 等待，provider 线程独立处理 HTTP 响应。
     */
    @Override
    public void streamChat(List<Message> history, List<Tool> tools, StreamCallback callback) {
        cancelled = false;
        executor.submit(() -> {
            try {
                doStreamChat(history, tools, callback);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    /**
     * 同步执行一次完整的 HTTP 请求 → SSE 解析流程。
     * 此方法在 provider 线程上运行，阻塞直到流结束或出错。
     */
    private void doStreamChat(List<Message> history, List<Tool> tools, StreamCallback callback) {
        try {
            String requestBody = buildRequestBody(history, tools);
            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            activeResponse = httpClient.newCall(request).execute();

            if (!activeResponse.isSuccessful()) {
                handleHttpError(activeResponse, callback);
                return;
            }

            parseSSEStream(activeResponse, callback);
        } catch (IOException e) {
            if (cancelled) {
                callback.onError(new IOException("已中断"));
            } else {
                callback.onError(new IOException("网络请求失败: " + e.getMessage(), e));
            }
        } finally {
            activeResponse = null;
        }
    }

    /**
     * 组装 Anthropic API 请求体。
     *
     * 三个关键转换：
     *   1. SYSTEM 消息 → 顶层 "system" 字段（不在 messages 数组里）
     *   2. Tool 列表 → tools 数组，schema 字段名为 input_schema（不是 OpenAI 的 parameters）
     *   3. Message 列表 → messages 数组，由 buildApiMessage 处理三种消息格式
     */
    @SuppressWarnings("unchecked")
    private String buildRequestBody(List<Message> history, List<Tool> tools) throws JsonProcessingException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);

        // System prompt: extract SYSTEM messages, join with newlines
        StringBuilder systemPrompt = new StringBuilder();
        for (Message msg : history) {
            if (msg.role() == Role.SYSTEM) {
                if (!systemPrompt.isEmpty()) {
                    systemPrompt.append("\n");
                }
                systemPrompt.append(msg.content());
            }
        }
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }

        // Tools: Anthropic 格式，使用 input_schema（非 parameters）
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (Tool tool : tools) {
                Map<String, Object> toolMap = new HashMap<>();
                toolMap.put("name", tool.getName());
                toolMap.put("description", tool.getDescription());
                toolMap.put("input_schema", objectMapper.readValue(tool.getParametersSchema(), Map.class));
                toolList.add(toolMap);
            }
            body.put("tools", toolList);
        }

        // Messages: USER/ASSISTANT（含 tool_calls 和 tool_results）
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message msg : history) {
            if (msg.role() == Role.USER || msg.role() == Role.ASSISTANT) {
                messages.add(buildApiMessage(msg));
            }
        }
        body.put("messages", messages);

        return objectMapper.writeValueAsString(body);
    }

    /**
     * 将内部 Message 转为 Anthropic API 单条消息格式。
     * 三种情况：
     *   纯文本          → {role, content: "字符串"}
     *   assistant+toolCalls → {role, content: "", tool_calls: [{id, type, name, input}]}
     *   user+toolResults    → {role, content: [{type: "tool_result", tool_use_id, content, is_error}]}
     *                        （注意 content 是数组，不是字符串）
     */
    private Map<String, Object> buildApiMessage(Message msg) {
        Map<String, Object> msgMap = new HashMap<>();
        msgMap.put("role", msg.role().name().toLowerCase());

        if (!msg.toolCalls().isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (ToolCall tc : msg.toolCalls()) {
                Map<String, Object> tcMap = new HashMap<>();
                tcMap.put("id", tc.id());
                tcMap.put("type", "tool_use");
                tcMap.put("name", tc.name());
                tcMap.put("input", tc.parameters());
                tcList.add(tcMap);
            }
            msgMap.put("content", msg.content() != null ? msg.content() : "");
            msgMap.put("tool_calls", tcList);
        } else if (!msg.toolResults().isEmpty()) {
            // User 消息含 tool_result
            List<Map<String, Object>> trList = new ArrayList<>();
            for (var tr : msg.toolResults()) {
                Map<String, Object> trMap = new HashMap<>();
                trMap.put("type", "tool_result");
                trMap.put("tool_use_id", tr.toolCallId());
                trMap.put("content", tr.content());
                if (tr.isError()) {
                    trMap.put("is_error", true);
                }
                trList.add(trMap);
            }
            msgMap.put("content", trList);
        } else {
            msgMap.put("content", msg.content());
        }

        return msgMap;
    }

    /**
     * SSE 事件流 → 回调。
     *
     * 纯文本回复的 SSE 事件顺序：
     *   message_start → content_block_start(text) → content_block_delta(text) × N
     *   → message_delta(stop_reason=end_turn) → message_stop
     *
     * 工具调用的 SSE 事件顺序：
     *   message_start → content_block_start(tool_use)×N → content_block_delta(input_json_delta)×N
     *   → message_delta(stop_reason=tool_use) → message_stop
     *   （message_stop 时按 index 顺序拼装 ToolCall → onToolCall）
     */
    @SuppressWarnings("unchecked")
    private void parseSSEStream(Response response, StreamCallback callback) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {

            // 多个 tool_use 块各自独立 index，必须用 Map 分别累积。
            // 不能用单值变量——第二个 content_block_start 会覆盖第一个的 id/name。
            Map<Integer, String> toolUseIds = new HashMap<>();
            Map<Integer, String> toolUseNames = new HashMap<>();
            Map<Integer, StringBuilder> toolUseJson = new HashMap<>();

            int inputTokens = 0;
            int outputTokens = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6);

                Map<String, Object> event = objectMapper.readValue(data, Map.class);
                String type = (String) event.get("type");

                if ("message_start".equals(type)) {
                    Map<String, Object> message = (Map<String, Object>) event.get("message");
                    if (message != null) {
                        Map<String, Object> usage = (Map<String, Object>) message.get("usage");
                        if (usage != null && usage.get("input_tokens") instanceof Number n) {
                            inputTokens = n.intValue();
                        }
                    }
                } else if ("content_block_start".equals(type)) {
                    Map<String, Object> contentBlock = (Map<String, Object>) event.get("content_block");
                    if (contentBlock != null) {
                        String blockType = (String) contentBlock.get("type");
                        if ("tool_use".equals(blockType)) {
                            int index = ((Number) event.get("index")).intValue();
                            toolUseIds.put(index, (String) contentBlock.get("id"));
                            toolUseNames.put(index, (String) contentBlock.get("name"));
                            toolUseJson.put(index, new StringBuilder());
                        } else if ("thinking".equals(blockType)) {
                            String thinking = (String) contentBlock.get("thinking");
                            if (thinking != null && !thinking.isEmpty()) {
                                callback.onReasoning(thinking);
                            }
                        }
                    }
                } else if ("content_block_delta".equals(type)) {
                    int index = ((Number) event.get("index")).intValue();
                    Map<String, Object> delta = (Map<String, Object>) event.get("delta");
                    if (delta != null) {
                        String deltaType = (String) delta.get("type");
                        if ("text_delta".equals(deltaType)) {
                            String text = (String) delta.get("text");
                            if (text != null && !text.isEmpty()) {
                                callback.onToken(text);
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            String json = (String) delta.get("partial_json");
                            if (json != null) {
                                StringBuilder sb = toolUseJson.get(index);
                                if (sb != null) sb.append(json);
                            }
                        } else if ("thinking_delta".equals(deltaType)) {
                            String thinking = (String) delta.get("thinking");
                            if (thinking != null && !thinking.isEmpty()) {
                                callback.onReasoning(thinking);
                            }
                        } else if ("signature_delta".equals(deltaType)) {
                            String signature = (String) delta.get("signature");
                            if (signature != null && !signature.isEmpty()) {
                                callback.onReasoning(signature);
                            }
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    Map<String, Object> usage = (Map<String, Object>) event.get("usage");
                    if (usage != null && usage.get("output_tokens") instanceof Number n) {
                        outputTokens = n.intValue();
                    }
                } else if ("message_stop".equals(type)) {
                    // 按 index 顺序逐个回调 onToolCall
                    for (int i = 0; i < toolUseIds.size(); i++) {
                        String tcId = toolUseIds.get(i);
                        String tcName = toolUseNames.get(i);
                        StringBuilder sb = toolUseJson.get(i);
                        if (tcId != null && tcName != null && sb != null) {
                            try {
                                Map<String, Object> params = objectMapper.readValue(
                                        sb.toString(), Map.class);
                                callback.onToolCall(new ToolCall(tcId, tcName, params));
                            } catch (JsonProcessingException e) {
                                callback.onError(new IOException(
                                        "解析工具调用参数失败: " + e.getMessage(), e));
                                return;
                            }
                        }
                    }
                    callback.onUsage(inputTokens, outputTokens);
                    callback.onComplete();
                    return;
                }
            }
        }
    }

    private void handleHttpError(Response response, StreamCallback callback) {
        String errorDetail;
        int code = response.code();
        try {
            errorDetail = response.body() != null ? response.body().string() : "无响应体";
        } catch (IOException e) {
            errorDetail = "无法读取错误响应";
        }
        response.close();

        String message;
        switch (code) {
            case 401:
                message = "API Key 无效 (HTTP 401)，请检查 ~/.bingtangcode/config 中的 anthropic.api_key";
                break;
            case 429:
                message = "请求频率超限 (HTTP 429)，请稍后重试";
                break;
            case 500:
            case 502:
            case 503:
                message = "Anthropic 服务端故障 (HTTP " + code + ")，请稍后重试";
                break;
            default:
                message = "API 请求失败 (HTTP " + code + "): " + errorDetail;
                break;
        }
        callback.onError(new IOException(message));
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    @Override
    public String getName() {
        return "Anthropic (" + model + ")";
    }
}
