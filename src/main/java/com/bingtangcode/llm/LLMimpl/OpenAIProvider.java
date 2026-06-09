package com.bingtangcode.llm.LLMimpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
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

public class OpenAIProvider implements LLMProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final int maxTokens;
    private volatile boolean cancelled;
    private volatile Response activeResponse;
    volatile String lastReasoning; // 最近一次思考全文，供外部展开查看

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public OpenAIProvider(String apiKey, String model, String endpoint, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
        this.maxTokens = maxTokens;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "openai-stream-reader");
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
     * 与 AnthropicProvider 完全相同的模式——提交到单线程 executor 后立即返回。
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

    private void doStreamChat(List<Message> history, List<Tool> tools, StreamCallback callback) {
        try {
            String requestBody = buildRequestBody(history, tools);
            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
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
     * 组装 OpenAI API 请求体。
     *
     * 三个关键转换（与 Anthropic 的差异见下方注释）：
     *   1. Tool 列表 → tools 数组，schema 字段名为 parameters 且包在 function 内
     *   2. assistant+toolCalls → {role:"assistant", tool_calls:[{id,type:"function",function:{name,arguments}}]}
     *   3. user+toolResults → {role:"tool", tool_call_id, content} —— 独立消息，非 content 数组
     * 注意: arguments 是 JSON 字符串（writeValueAsString），不是 JSON 对象，与 Anthropic 不同。
     */
    @SuppressWarnings("unchecked")
    private String buildRequestBody(List<Message> history, List<Tool> tools) throws JsonProcessingException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));

        // Tools: OpenAI 格式 {type: "function", function: {name, description, parameters}}
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (Tool tool : tools) {
                Map<String, Object> function = new HashMap<>();
                function.put("name", tool.getName());
                function.put("description", tool.getDescription());
                function.put("parameters", objectMapper.readValue(tool.getParametersSchema(), Map.class));

                Map<String, Object> toolMap = new HashMap<>();
                toolMap.put("type", "function");
                toolMap.put("function", function);
                toolList.add(toolMap);
            }
            body.put("tools", toolList);
        }

        // Messages: 含 tool_calls 的 assistant 和 role="tool" 的 tool_result
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message msg : history) {
            if (!msg.toolCalls().isEmpty()) {
                // Assistant 消息含 tool_calls
                Map<String, Object> msgMap = new HashMap<>();
                msgMap.put("role", "assistant");
                msgMap.put("content", msg.content() != null ? msg.content() : "");

                List<Map<String, Object>> tcList = new ArrayList<>();
                for (ToolCall tc : msg.toolCalls()) {
                    Map<String, Object> function = new HashMap<>();
                    function.put("name", tc.name());
                    function.put("arguments", objectMapper.writeValueAsString(tc.parameters()));

                    Map<String, Object> tcMap = new HashMap<>();
                    tcMap.put("id", tc.id());
                    tcMap.put("type", "function");
                    tcMap.put("function", function);
                    tcList.add(tcMap);
                }
                msgMap.put("tool_calls", tcList);
                messages.add(msgMap);
            } else if (!msg.toolResults().isEmpty()) {
                // tool_result → OpenAI role="tool" 消息（每条结果一条消息）
                for (var tr : msg.toolResults()) {
                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("role", "tool");
                    msgMap.put("tool_call_id", tr.toolCallId());
                    msgMap.put("content", tr.content());
                    messages.add(msgMap);
                }
            } else {
                Map<String, Object> msgMap = new HashMap<>();
                msgMap.put("role", msg.role().name().toLowerCase());
                msgMap.put("content", msg.content());
                messages.add(msgMap);
            }
        }
        body.put("messages", messages);

        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private void parseSSEStream(Response response, StreamCallback callback) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {

            Map<Integer, String> toolCallIds = new HashMap<>();
            Map<Integer, String> toolCallNames = new HashMap<>();
            Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();

            // 思考过程：灰色流式展示，结束后加分隔线与正文区分
            StringBuilder reasoningBuf = new StringBuilder();
            boolean reasoningDone = false;

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6);

                if ("[DONE]".equals(data.trim())) {
                    callback.onComplete();
                    return;
                }

                Map<String, Object> event = objectMapper.readValue(data, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) event.get("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }

                Map<String, Object> choice = choices.get(0);

                // finish_reason 出现时流结束。
                // "tool_calls" → 模型要调工具，先 onToolCall 再 onComplete
                // "stop"/"length" → 纯文本结束，直接 onComplete
                String finishReason = (String) choice.get("finish_reason");
                if (finishReason != null) {
                    if (!reasoningDone && !reasoningBuf.isEmpty()) {
                        lastReasoning = reasoningBuf.toString();
                        System.out.println("\033[90m── 思考结束 ──\033[0m");
                    }
                    if ("tool_calls".equals(finishReason)) {
                        // 按 index 升序逐个回调——先收集的工具先回调
                        for (int i = 0; i < toolCallIds.size(); i++) {
                            String tcId = toolCallIds.get(i);
                            String tcName = toolCallNames.get(i);
                            StringBuilder args = toolCallArgs.get(i);
                            if (tcId != null && tcName != null && args != null) {
                                try {
                                    Map<String, Object> params = objectMapper.readValue(
                                            args.toString(), Map.class);
                                    callback.onToolCall(new ToolCall(tcId, tcName, params));
                                } catch (JsonProcessingException e) {
                                    callback.onError(new IOException(
                                            "解析工具调用参数失败: " + e.getMessage(), e));
                                    return;
                                }
                            }
                        }
                    }
                    callback.onComplete();
                    return;
                }

                Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                if (delta == null) {
                    continue;
                }

                // 思考过程：灰色流式展示
                String reasoning = (String) delta.get("reasoning_content");
                if (reasoning != null && !reasoning.isEmpty()) {
                    reasoningBuf.append(reasoning);
                    System.out.print("\033[90m" + reasoning + "\033[0m");
                }

                // 文本增量
                String text = (String) delta.get("content");
                if (text != null && !text.isEmpty()) {
                    if (!reasoningDone && !reasoningBuf.isEmpty()) {
                        reasoningDone = true;
                        lastReasoning = reasoningBuf.toString();
                        System.out.println("\033[90m── 思考结束 ──\033[0m");
                    }
                    callback.onToken(text);
                }

                // 工具调用增量
                List<Map<String, Object>> tcDeltas = (List<Map<String, Object>>) delta.get("tool_calls");
                if (tcDeltas != null) {
                    for (Map<String, Object> tcDelta : tcDeltas) {
                        int index = ((Number) tcDelta.get("index")).intValue();

                        String tcId = (String) tcDelta.get("id");
                        if (tcId != null) {
                            toolCallIds.put(index, tcId);
                        }

                        Map<String, Object> function = (Map<String, Object>) tcDelta.get("function");
                        if (function != null) {
                            String name = (String) function.get("name");
                            if (name != null) {
                                toolCallNames.put(index, name);
                            }
                            String args = (String) function.get("arguments");
                            if (args != null) {
                                toolCallArgs.computeIfAbsent(index, k -> new StringBuilder()).append(args);
                            }
                        }
                    }
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
                message = "API Key 无效 (HTTP 401)，请检查 ~/.bingtangcode/config 中的 openai.api_key";
                break;
            case 429:
                message = "请求频率超限 (HTTP 429)，请稍后重试";
                break;
            case 500:
            case 502:
            case 503:
                message = "OpenAI 服务端故障 (HTTP " + code + ")，请稍后重试";
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
        return "OpenAI (" + model + ")";
    }
}
