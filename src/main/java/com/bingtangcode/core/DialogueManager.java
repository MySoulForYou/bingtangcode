package com.bingtangcode.core;

import com.bingtangcode.agent.AgentEvent;
import com.bingtangcode.agent.EventBus;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolExecutor;
import com.bingtangcode.tool.ToolRegistry;
import com.bingtangcode.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class DialogueManager {

    private final List<Message> history;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ExecutorService batchExecutor;

    public DialogueManager(String systemPrompt, ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor, List<Tool> tools) {
        this.history = new ArrayList<>();
        this.history.add(new Message(Role.SYSTEM, systemPrompt));
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.batchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dialogue-batch-tool");
            t.setDaemon(true);
            return t;
        });
    }

    public void addUserMessage(String content) {
        history.add(new Message(Role.USER, content));
    }

    public void addMessage(Message message) {
        history.add(message);
    }

    /**
     * 同步执行一轮 LLM 请求 + 可选的工具执行。
     * 内部用 CountDownLatch 等待异步 streamChat 完成，调用方线程阻塞直到本轮结束。
     */
    public RoundResult doRound(LLMProvider provider, List<Tool> currentTools, EventBus eventBus,
                               AtomicInteger totalInputTokens, AtomicInteger totalOutputTokens) {
        List<Message> messages = buildApiMessages();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> streamError = new AtomicReference<>();
        StringBuilder textBuilder = new StringBuilder();
        List<ToolCall> pendingToolCalls = new ArrayList<>();

        StreamCallback collector = new StreamCallback() {
            @Override
            public void onToken(String token) {
                textBuilder.append(token);
                eventBus.fire(new AgentEvent.TextDelta(token));
            }

            @Override
            public void onReasoning(String token) {
                eventBus.fire(new AgentEvent.ReasoningDelta(token));
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                pendingToolCalls.add(toolCall);
            }

            @Override
            public void onUsage(int inputTokens, int outputTokens) {
                totalInputTokens.addAndGet(inputTokens);
                totalOutputTokens.addAndGet(outputTokens);
                eventBus.fire(new AgentEvent.TokenUsage(
                        inputTokens, outputTokens,
                        totalInputTokens.get(), totalOutputTokens.get()));
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

        provider.streamChat(messages, currentTools, collector);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("doRound interrupted", e);
        }

        if (streamError.get() != null) {
            throw new RuntimeException("stream error", streamError.get());
        }

        if (pendingToolCalls.isEmpty()) {
            history.add(new Message(Role.ASSISTANT, textBuilder.toString()));
            return RoundResult.COMPLETED;
        }

        boolean hasUnknown = false;
        for (ToolCall tc : pendingToolCalls) {
            if (toolRegistry.get(tc.name()) == null) {
                hasUnknown = true;
                break;
            }
        }

        history.add(new Message(Role.ASSISTANT, textBuilder.toString(), pendingToolCalls, List.of()));
        List<ToolResult> results = executeToolBatch(pendingToolCalls, eventBus);
        history.add(new Message(Role.USER, "", List.of(), results));

        return new RoundResult(false, pendingToolCalls, textBuilder.toString(), hasUnknown);
    }

    public List<Message> buildApiMessages() {
        List<Message> cleaned = new ArrayList<>();
        for (Message msg : history) {
            if (msg.role() == Role.SYSTEM) {
                cleaned.add(msg);
                continue;
            }
            int size = cleaned.size();
            if (size > 0 && cleaned.get(size - 1).role() == msg.role() && msg.role() != Role.SYSTEM) {
                Message prev = cleaned.get(size - 1);
                cleaned.set(size - 1, new Message(prev.role(),
                        prev.content() + "\n" + msg.content(),
                        merge(prev.toolCalls(), msg.toolCalls()),
                        merge(prev.toolResults(), msg.toolResults())));
            } else {
                cleaned.add(msg);
            }
        }
        if (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).role() != Role.USER) {
            throw new IllegalStateException("对话历史最后一条必须是 USER 消息");
        }
        return cleaned;
    }

    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }

    // ==================== private ====================

    private List<ToolResult> executeToolBatch(List<ToolCall> toolCalls, EventBus eventBus) {
        List<ToolCall> readOnly = new ArrayList<>();
        List<ToolCall> mutation = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Tool tool = toolRegistry.get(tc.name());
            if (tool != null && tool.isReadOnly()) {
                readOnly.add(tc);
            } else {
                mutation.add(tc);
            }
        }

        int total = toolCalls.size();
        List<ToolResult> results = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            results.add(null);
        }

        List<Future<?>> futures = new ArrayList<>();
        for (ToolCall tc : readOnly) {
            futures.add(batchExecutor.submit(() -> executeOne(tc, results, toolCalls, eventBus)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }

        for (ToolCall tc : mutation) {
            executeOne(tc, results, toolCalls, eventBus);
        }

        return results;
    }

    private void executeOne(ToolCall tc, List<ToolResult> results,
                            List<ToolCall> allCalls, EventBus eventBus) {
        eventBus.fire(new AgentEvent.ToolCallStarted(tc.name(), tc.id()));
        long start = System.currentTimeMillis();

        Tool tool = toolRegistry.get(tc.name());
        ToolResult result;
        if (tool == null) {
            result = new ToolResult(tc.id(), "未找到工具: " + tc.name(), true);
        } else {
            result = toolExecutor.execute(tool, tc.id(), tc.parameters());
        }

        long elapsed = System.currentTimeMillis() - start;
        eventBus.fire(new AgentEvent.ToolCallCompleted(tc.name(), tc.id(), result.isError(), elapsed));

        int idx = allCalls.indexOf(tc);
        if (idx >= 0) {
            results.set(idx, result);
        }
    }

    private static <T> List<T> merge(List<T> a, List<T> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<T> merged = new ArrayList<>(a);
        merged.addAll(b);
        return Collections.unmodifiableList(merged);
    }
}
