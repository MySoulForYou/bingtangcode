package com.bingtangcode.llm;

import com.bingtangcode.tool.ToolCall;

public interface StreamCallback {

    void onToken(String token);

    /** LLM 流式解析完一个完整的 tool_use 块时回调，由各 Provider 在 SSE 解析中触发 */
    default void onToolCall(ToolCall toolCall) {
    }

    /** 模型输出推理/思考内容时逐 token 回调 */
    default void onReasoning(String token) {
    }

    void onComplete();

    void onError(Exception e);

    /** LLM 请求完成后的 token 用量。各 Provider 在流结束时回调此方法 */
    default void onUsage(int inputTokens, int outputTokens) {
    }
}
