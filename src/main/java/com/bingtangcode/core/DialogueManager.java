package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DialogueManager {

    private final List<Message> history;// 对话历史列表

    public DialogueManager(String systemPrompt) {
        this.history = new ArrayList<>();
        this.history.add(new Message(Role.SYSTEM, systemPrompt));
    }

    public void addUserMessage(String content) {
        history.add(new Message(Role.USER, content));
    }

    public void streamResponse(LLMProvider provider, StreamCallback callback) {
        StringBuilder sb = new StringBuilder();

        StreamCallback wrapper = new StreamCallback() {
            @Override
            public void onToken(String token) {
                sb.append(token);
                callback.onToken(token);
            }

            @Override
            public void onComplete() {
                history.add(new Message(Role.ASSISTANT, sb.toString()));
                callback.onComplete();
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        };

        List<Message> apiMessages = buildApiMessages();
        provider.streamChat(apiMessages, wrapper);
    }

    List<Message> buildApiMessages() {
        List<Message> cleaned = new ArrayList<>();
        for (Message msg : history) {
            if (msg.role() == Role.SYSTEM) {
                cleaned.add(msg);
                continue;
            }
            // 合并连续相同角色：相邻两条 USER 或相邻两条 ASSISTANT 拼成一条
            int size = cleaned.size();
            if (size > 0 && cleaned.get(size - 1).role() == msg.role() && msg.role() != Role.SYSTEM) {
                Message prev = cleaned.get(size - 1);
                cleaned.set(size - 1, new Message(prev.role(), prev.content() + "\n" + msg.content()));
            } else {
                cleaned.add(msg);
            }
        }
        // 确保最后一条是 USER（Anthropic API 要求）
        if (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).role() != Role.USER) {
            throw new IllegalStateException("对话历史最后一条必须是 USER 消息");
        }
        return cleaned;
    }

    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }
}
