package com.bingtangcode.llm;

import com.bingtangcode.tool.Tool;

import java.util.List;

public interface LLMProvider {

    void streamChat(List<Message> messages, List<Tool> tools, StreamCallback callback);

    void shutdown();

    String getName();
}
