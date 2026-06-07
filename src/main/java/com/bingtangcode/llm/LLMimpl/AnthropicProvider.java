package com.bingtangcode.llm.LLMimpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
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

    @Override
    public void streamChat(List<Message> history, StreamCallback callback) {
        cancelled = false;
        executor.submit(() -> {
            try {
                doStreamChat(history, callback);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    private void doStreamChat(List<Message> history, StreamCallback callback) {
        try {
            String requestBody = buildRequestBody(history);
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

    private String buildRequestBody(List<Message> history) throws JsonProcessingException {
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

        // Messages: only USER and ASSISTANT
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message msg : history) {
            if (msg.role() == Role.USER || msg.role() == Role.ASSISTANT) {
                Map<String, Object> msgMap = new HashMap<>();
                msgMap.put("role", msg.role().name().toLowerCase());
                msgMap.put("content", msg.content());
                messages.add(msgMap);
            }
        }
        body.put("messages", messages);

        return objectMapper.writeValueAsString(body);
    }

    private void parseSSEStream(Response response, StreamCallback callback) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // SSE lines start with "data: "
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6);

                @SuppressWarnings("unchecked")
                Map<String, Object> event = objectMapper.readValue(data, Map.class);
                String type = (String) event.get("type");

                if ("content_block_delta".equals(type)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> delta = (Map<String, Object>) event.get("delta");
                    if (delta != null && "text_delta".equals(delta.get("type"))) {
                        String text = (String) delta.get("text");
                        if (text != null && !text.isEmpty()) {
                            callback.onToken(text);
                        }
                    }
                } else if ("message_stop".equals(type)) {
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
