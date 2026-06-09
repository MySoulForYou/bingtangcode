package com.bingtangcode.tool;

import java.util.Map;

/**
 * 一次工具调用——LLM 流式解析后得到的内部表示。
 * 由各 LLMProvider 在流式解析过程中构造，通过 StreamCallback.onToolCall 回调给 DialogueManager。
 */
public record ToolCall(String id, String name, Map<String, Object> parameters) {
}
