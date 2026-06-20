package com.bingtangcode.core;

import com.bingtangcode.agent.AgentEvent;
import com.bingtangcode.agent.EventBus;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.permission.PermissionGate;
import com.bingtangcode.permission.PermissionResult;
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

    private final String sessionId;
    private final int contextWindow;
    private final int toolResultLimit;
    private final int toolResultTotalLimit;
    private final int contextSummaryReserve;
    private final int contextAutoCompressMargin;
    private final int contextManualCompressMargin;
    private final int contextKeepRecentTokens;
    private final int contextKeepRecentMessages;
    private final int contextMaxCompressFailures;
    private final double contextCharToTokenRatio;
    private final int toolResultPreviewLimit;
    private final int toolResultPreviewLines;

    private final List<Message> history;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private volatile PermissionGate permissionGate;
    private final ExecutorService batchExecutor;
    private final SessionPersister sessionPersister;

    int lastApiInputTokens = 0;
    int lastApiOutputTokens = 0;
    int lastApiHistorySize = 0;
    private int compressFailureCount = 0;
    private boolean autoCompressEnabled = true;

    // 接收 ConfigManager 的高级构造函数，方便主流程装配
    public DialogueManager(String systemPrompt, ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor, PermissionGate permissionGate,
                           List<Tool> tools, String sessionId, com.bingtangcode.config.ConfigManager config) {
        this(systemPrompt, toolRegistry, toolExecutor, permissionGate, tools, sessionId,
             config.getContextWindow(), config.getToolResultLimit(), config.getToolResultTotalLimit(),
             config.getContextSummaryReserve(), config.getContextAutoCompressMargin(),
             config.getContextManualCompressMargin(), config.getContextKeepRecentTokens(),
             config.getContextKeepRecentMessages(), config.getContextMaxCompressFailures(),
             config.getContextCharToTokenRatio(), config.getToolResultPreviewLimit(),
             config.getToolResultPreviewLines());
    }

    // 保留旧构造函数，以实现测试与现有调用链兼容
    public DialogueManager(String systemPrompt, ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor, PermissionGate permissionGate,
                           List<Tool> tools, String sessionId, int contextWindow,
                           int toolResultLimit, int toolResultTotalLimit) {
        this(systemPrompt, toolRegistry, toolExecutor, permissionGate, tools, sessionId,
             contextWindow, toolResultLimit, toolResultTotalLimit,
             20000, 13000, 3000, 10000, 5, 3, 3.5, 2048, 20);
    }

    // 全参数构造函数
    public DialogueManager(String systemPrompt, ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor, PermissionGate permissionGate,
                           List<Tool> tools, String sessionId, int contextWindow,
                           int toolResultLimit, int toolResultTotalLimit,
                           int contextSummaryReserve, int contextAutoCompressMargin,
                           int contextManualCompressMargin, int contextKeepRecentTokens,
                           int contextKeepRecentMessages, int contextMaxCompressFailures,
                           double contextCharToTokenRatio, int toolResultPreviewLimit,
                           int toolResultPreviewLines) {
        this.sessionId = sessionId;
        this.contextWindow = contextWindow;
        this.toolResultLimit = toolResultLimit;
        this.toolResultTotalLimit = toolResultTotalLimit;
        this.contextSummaryReserve = contextSummaryReserve;
        this.contextAutoCompressMargin = contextAutoCompressMargin;
        this.contextManualCompressMargin = contextManualCompressMargin;
        this.contextKeepRecentTokens = contextKeepRecentTokens;
        this.contextKeepRecentMessages = contextKeepRecentMessages;
        this.contextMaxCompressFailures = contextMaxCompressFailures;
        this.contextCharToTokenRatio = contextCharToTokenRatio;
        this.toolResultPreviewLimit = toolResultPreviewLimit;
        this.toolResultPreviewLines = toolResultPreviewLines;
        this.sessionPersister = new SessionPersister(java.nio.file.Paths.get("").toAbsolutePath());
        this.history = new ArrayList<>();
        Message sysMsg = new Message(Role.SYSTEM, systemPrompt);
        this.history.add(sysMsg);
        try {
            java.nio.file.Path sessionFile = java.nio.file.Paths.get("").toAbsolutePath()
                    .resolve(".bingtangcode").resolve("sessions").resolve(sessionId + ".jsonl");
            if (!java.nio.file.Files.exists(sessionFile)) {
                sessionPersister.appendMessage(sessionId, sysMsg);
            }
        } catch (Exception ignored) {}
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.permissionGate = permissionGate;
        this.batchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dialogue-batch-tool");
            t.setDaemon(true);
            return t;
        });
    }

    public void setPermissionGate(PermissionGate gate) {
        this.permissionGate = gate;
    }

    public void addUserMessage(String content) {
        Message msg = new Message(Role.USER, content);
        history.add(msg);
        sessionPersister.appendMessage(sessionId, msg);
    }

    public void addMessage(Message message) {
        history.add(message);
        sessionPersister.appendMessage(sessionId, message);
    }

    /**
     * 同步执行一轮 LLM 请求 + 可选的工具执行。
     * 内部用 CountDownLatch 等待异步 streamChat 完成，调用方线程阻塞直到本轮结束。
     */
    public RoundResult doRound(LLMProvider provider, List<Tool> currentTools, EventBus eventBus,
                               AtomicInteger totalInputTokens, AtomicInteger totalOutputTokens) {
        if (autoCompressEnabled) {
            int currentEstimate = estimateCurrentTokens();
            if (currentEstimate >= contextWindow - contextSummaryReserve - contextAutoCompressMargin) {
                try {
                    compressHistory(provider, false);
                } catch (Exception e) {
                    System.err.println("[系统警告] 自动历史压缩失败，但不影响当前对话继续: " + e.getMessage());
                }
            }
        }

        final int historySizeAtRequest = history.size();
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
                lastApiInputTokens = inputTokens;
                lastApiOutputTokens = outputTokens;
                lastApiHistorySize = historySizeAtRequest;
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
            Message msg = new Message(Role.ASSISTANT, textBuilder.toString());
            history.add(msg);
            sessionPersister.appendMessage(sessionId, msg);
            return RoundResult.COMPLETED;
        }

        boolean hasUnknown = false;
        for (ToolCall tc : pendingToolCalls) {
            if (toolRegistry.get(tc.name()) == null) {
                hasUnknown = true;
                break;
            }
        }

        Message assistantMsg = new Message(Role.ASSISTANT, textBuilder.toString(), pendingToolCalls, List.of());
        history.add(assistantMsg);
        sessionPersister.appendMessage(sessionId, assistantMsg);
        List<ToolResult> results = executeToolBatch(pendingToolCalls, eventBus);

        // 检查是否本轮所有工具调用都被权限拒绝
        boolean allPermissionDenied = !pendingToolCalls.isEmpty() && !results.isEmpty();
        if (allPermissionDenied) {
            for (ToolResult r : results) {
                if (r.content() == null || !r.content().startsWith("权限拒绝")) {
                    allPermissionDenied = false;
                    break;
                }
            }
        }

        List<ToolResult> processedResults = preventOverrunAndSave(results);
        Message userResultMsg = new Message(Role.USER, "", List.of(), processedResults);
        history.add(userResultMsg);
        sessionPersister.appendMessage(sessionId, userResultMsg);

        return new RoundResult(false, pendingToolCalls, textBuilder.toString(),
                hasUnknown, allPermissionDenied);
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
        return history; // Return mutable history so it can be cleared/manipulated during recovery/testing
    }

    public void restoreHistory(List<Message> restoredHistory) {
        this.history.clear();
        this.history.addAll(restoredHistory);
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getContextWindow() {
        return contextWindow;
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
        } else if (permissionGate != null) {
            PermissionResult pr = permissionGate.check(tc);
            if (!pr.allowed()) {
                String friendlyName = com.bingtangcode.permission.ToolFriendlyName.friendlyName(tc.name());
                String mainParam = com.bingtangcode.permission.ToolFriendlyName.extractMainParam(tc.name(), tc.parameters());
                String errorMsg = "权限拒绝: " + friendlyName
                        + (mainParam != null ? "(" + mainParam + ")" : "")
                        + " 被 " + (pr.reason() != null ? pr.reason() : "权限规则") + " 拦截\n"
                        + "  匹配规则: " + (pr.matchedRule() != null ? pr.matchedRule() : "无");
                result = new ToolResult(tc.id(), errorMsg, true);
            } else {
                result = toolExecutor.execute(tool, tc.id(), tc.parameters());
            }
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

    public int estimateCurrentTokens() {
        if (lastApiInputTokens <= 0) {
            int totalChars = 0;
            for (Message msg : history) {
                totalChars += estimateMessageChars(msg);
            }
            return (int) Math.ceil(totalChars / contextCharToTokenRatio);
        }

        int totalChars = 0;
        if (history.size() > lastApiHistorySize + 1) {
            for (int i = lastApiHistorySize + 1; i < history.size(); i++) {
                totalChars += estimateMessageChars(history.get(i));
            }
        }
        return lastApiInputTokens + lastApiOutputTokens + (int) Math.ceil(totalChars / contextCharToTokenRatio);
    }

    int estimateMessageChars(Message msg) {
        int chars = 0;
        if (msg.content() != null) {
            chars += msg.content().length();
        }
        if (msg.toolCalls() != null) {
            for (com.bingtangcode.tool.ToolCall tc : msg.toolCalls()) {
                chars += tc.name().length();
                chars += tc.id().length();
                if (tc.parameters() != null) {
                    chars += tc.parameters().toString().length();
                }
            }
        }
        if (msg.toolResults() != null) {
            for (com.bingtangcode.tool.ToolResult tr : msg.toolResults()) {
                if (tr.content() != null) {
                    chars += tr.content().length();
                }
            }
        }
        return chars;
    }

    List<ToolResult> preventOverrunAndSave(List<ToolResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        List<ToolResult> processed = new ArrayList<>();
        boolean[] isSaved = new boolean[results.size()];
        long[] sizes = new long[results.size()];

        for (int i = 0; i < results.size(); i++) {
            ToolResult tr = results.get(i);
            if (tr == null || tr.content() == null) {
                processed.add(tr);
                continue;
            }
            byte[] bytes = tr.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sizes[i] = bytes.length;

            if (bytes.length > toolResultLimit) {
                ToolResult newTr = saveAndReplaceResult(tr, bytes.length);
                processed.add(newTr);
                isSaved[i] = true;
            } else {
                processed.add(tr);
            }
        }

        long totalUnsavedSize = 0;
        for (int i = 0; i < processed.size(); i++) {
            if (!isSaved[i] && processed.get(i) != null && processed.get(i).content() != null) {
                totalUnsavedSize += sizes[i];
            }
        }

        if (totalUnsavedSize > toolResultTotalLimit) {
            List<Integer> unsavedIndices = new ArrayList<>();
            for (int i = 0; i < processed.size(); i++) {
                if (!isSaved[i] && processed.get(i) != null && processed.get(i).content() != null) {
                    unsavedIndices.add(i);
                }
            }
            unsavedIndices.sort((a, b) -> Long.compare(sizes[b], sizes[a]));

            for (int idx : unsavedIndices) {
                if (totalUnsavedSize <= toolResultTotalLimit) {
                    break;
                }
                ToolResult tr = processed.get(idx);
                ToolResult newTr = saveAndReplaceResult(tr, sizes[idx]);
                processed.set(idx, newTr);
                isSaved[idx] = true;
                totalUnsavedSize -= sizes[idx];
            }
        }

        return processed;
    }

    ToolResult saveAndReplaceResult(ToolResult tr, long originalSize) {
        String toolCallId = tr.toolCallId();
        if (toolCallId == null || toolCallId.isBlank()) {
            toolCallId = "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        java.nio.file.Path dir = java.nio.file.Paths.get(".bingtangcode", "sessions", sessionId, "tool-results");
        java.nio.file.Path file = dir.resolve(toolCallId);

        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(file, tr.content(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[错误] 无法保存工具执行结果到文件: " + e.getMessage());
            return tr;
        }

        String absolutePath = file.toAbsolutePath().toString();
        String headerPreview = getHeaderPreview(tr.content());

        String replacedContent = "[工具执行结果过大已落盘保护]\n" +
                "原始大小: " + originalSize + " 字节\n" +
                "保存路径: " + absolutePath + "\n" +
                "重读提示: 如果需要阅读该文件的完整内容，请显式使用 ReadFile 工具重新读取对应路径，不要依赖本预览。\n\n" +
                "头部预览内容如下:\n" +
                "--------------------------------------\n" +
                headerPreview + "\n" +
                "--------------------------------------";

        return new ToolResult(tr.toolCallId(), replacedContent, tr.isError());
    }

    private String getHeaderPreview(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\\r?\\n", toolResultPreviewLines + 2);
        StringBuilder sb = new StringBuilder();
        int lineCount = Math.min(lines.length, toolResultPreviewLines);
        for (int i = 0; i < lineCount; i++) {
            sb.append(lines[i]);
            if (i < lineCount - 1) {
                sb.append("\n");
            }
        }
        String linePreview = sb.toString();

        int byteLen = 0;
        int charIndex = 0;
        while (charIndex < linePreview.length()) {
            int codePoint = linePreview.codePointAt(charIndex);
            int charCount = Character.charCount(codePoint);
            int cpByteLen = new String(Character.toChars(codePoint)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (byteLen + cpByteLen > toolResultPreviewLimit) {
                break;
            }
            byteLen += cpByteLen;
            charIndex += charCount;
        }
        return linePreview.substring(0, charIndex);
    }

    public synchronized void compressHistory(LLMProvider provider, boolean manual) throws Exception {
        if (!manual && !autoCompressEnabled) {
            return;
        }

        int keepCount = 0;
        int keepChars = 0;
        int lastIndexToKeep = history.size() - 1;

        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.role() == Role.SYSTEM) {
                break;
            }
            keepCount++;
            keepChars += estimateMessageChars(msg);
            lastIndexToKeep = i;

            double keepTokens = keepChars / contextCharToTokenRatio;
            if (keepTokens >= contextKeepRecentTokens && keepCount >= contextKeepRecentMessages) {
                break;
            }
        }

        if (lastIndexToKeep <= 1) {
            return;
        }

        List<Message> historyToSummarize = new ArrayList<>(history.subList(1, lastIndexToKeep));

        try {
            System.out.println("\n[系统] 正在进行对话历史压缩...");
            long start = System.currentTimeMillis();

            String summary = ContextCompressor.generateSummary(provider, historyToSummarize);

            String boundaryReminder = "\n\n📖 重要提示：\n" +
                    "- 需要文件原文时，请使用 ReadFile 工具重新读取对应路径\n" +
                    "- 需要错误原文或用户原话时，请追溯摘要中的记录或重新读取\n" +
                    "- 不要依据摘要内容做猜测性补全，摘要仅作为索引而非精确原文";

            String mergedContent = summary + boundaryReminder;
            Message summaryMsg = new Message(Role.USER, mergedContent);

            List<Message> newHistory = new ArrayList<>();
            newHistory.add(history.get(0)); // system
            newHistory.add(summaryMsg);
            newHistory.addAll(history.subList(lastIndexToKeep, history.size()));

            this.history.clear();
            this.history.addAll(newHistory);

            compressFailureCount = 0;
            lastApiInputTokens = 0;
            lastApiOutputTokens = 0;
            lastApiHistorySize = 0;

            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[系统] 历史压缩完成，用时 " + elapsed + " ms");

        } catch (Exception e) {
            compressFailureCount++;
            System.err.println("[警告] 生成历史摘要失败 (" + compressFailureCount + "/" + contextMaxCompressFailures + "): " + e.getMessage());
            if (compressFailureCount >= contextMaxCompressFailures) {
                autoCompressEnabled = false;
                System.err.println("[熔断] 摘要连续失败 " + contextMaxCompressFailures + " 次，已关闭自动上下文压缩。");
            }
            throw e;
        }
    }

    public int getContextSummaryReserve() {
        return contextSummaryReserve;
    }

    public int getContextManualCompressMargin() {
        return contextManualCompressMargin;
    }

    public double getContextCharToTokenRatio() {
        return contextCharToTokenRatio;
    }
}
