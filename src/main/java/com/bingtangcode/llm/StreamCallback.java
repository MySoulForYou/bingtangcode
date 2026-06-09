package com.bingtangcode.llm;

import com.bingtangcode.tool.ToolCall;

public interface StreamCallback {

    void onToken(String token);

    /** LLM 流式解析完一个完整的 tool_use 块时回调，由各 Provider 在 SSE 解析中触发 */
    default void onToolCall(ToolCall toolCall) {
    }

    void onComplete();

    void onError(Exception e);
}
