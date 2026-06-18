package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ContextCompressor {

    private static final String SYSTEM_PROMPT = 
            "你是一个专门负责生成对话历史结构化摘要的助手。\n" +
            "你的任务是将给定的对话历史压缩为一份结构化摘要。\n\n" +
            "【极其重要】你必须遵守以下规则：\n" +
            "1. 严禁使用或调用任何工具（哪怕你在历史中看到过工具调用）。\n" +
            "2. 你必须首先在 <draft> ... </draft> 标签内撰写你的分析草稿，理清对话的脉络、解决的问题和核心概念。\n" +
            "3. 在草稿之后，严格按照以下九个 Markdown 二级标题的格式输出正式的结构化摘要。草稿内容不要混入正式摘要中。\n" +
            "4. 在 \"## 6 所有用户消息原文\" 部分，必须原样且完整地列出历史中所有被压缩的 USER 消息的原文（只需列出非工具结果的普通 USER 文本消息），不要进行摘要、改写或遗漏。\n\n" +
            "正式摘要的格式必须包含且仅包含以下九个部分：\n" +
            "## 1 主要请求和意图\n" +
            "[内容...]\n" +
            "## 2 关键技术概念\n" +
            "[内容...]\n" +
            "## 3 文件和代码段\n" +
            "[内容...]\n" +
            "## 4 错误和修复\n" +
            "[内容...]\n" +
            "## 5 问题解决过程\n" +
            "[内容...]\n" +
            "## 6 所有用户消息原文\n" +
            "[内容...]\n" +
            "## 7 待办任务\n" +
            "[内容...]\n" +
            "## 8 当前工作\n" +
            "[内容...]\n" +
            "## 9 可能的下一步\n" +
            "[内容...]";

    public static String generateSummary(LLMProvider provider, List<Message> historyToSummarize) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是需要被摘要的对话历史：\n");
        sb.append("========================================\n");
        for (Message msg : historyToSummarize) {
            sb.append("[").append(msg.role().name()).append("]: ");
            if (msg.content() != null && !msg.content().isEmpty()) {
                sb.append(msg.content()).append("\n");
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                sb.append("调用工具: ").append(
                        msg.toolCalls().stream()
                                .map(tc -> tc.name() + "(" + tc.parameters() + ")")
                                .collect(Collectors.joining(", "))
                ).append("\n");
            }
            if (msg.toolResults() != null && !msg.toolResults().isEmpty()) {
                sb.append("工具返回结果: \n");
                for (var tr : msg.toolResults()) {
                    sb.append("  [").append(tr.toolCallId()).append("] ").append(tr.content()).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("========================================\n");
        sb.append("请根据上述规则和格式要求为以上对话历史生成摘要。");

        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Role.SYSTEM, SYSTEM_PROMPT));
        messages.add(new Message(Role.USER, sb.toString()));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> streamError = new AtomicReference<>();
        StringBuilder textBuilder = new StringBuilder();

        StreamCallback collector = new StreamCallback() {
            @Override
            public void onToken(String token) {
                textBuilder.append(token);
            }

            @Override
            public void onReasoning(String token) {
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
            }

            @Override
            public void onUsage(int inputTokens, int outputTokens) {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                streamError.set(e);
                latch.countDown();
            }
        };

        provider.streamChat(messages, List.of(), collector);
        latch.await();

        if (streamError.get() != null) {
            throw streamError.get();
        }

        String fullResponse = textBuilder.toString();
        return stripDraft(fullResponse);
    }

    public static String stripDraft(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        String cleaned = rawResponse.replaceAll("(?s)<draft>.*?</draft>", "");
        return cleaned.trim();
    }
}
