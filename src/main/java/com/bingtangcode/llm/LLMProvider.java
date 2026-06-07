package com.bingtangcode.llm;

import java.util.List;

public interface LLMProvider {

    void streamChat(List<Message> history, StreamCallback callback);

    void shutdown();

    String getName();
}
