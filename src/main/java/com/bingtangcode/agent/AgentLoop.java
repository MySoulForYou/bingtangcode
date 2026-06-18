package com.bingtangcode.agent;

import com.bingtangcode.core.DialogueManager;
import com.bingtangcode.core.RoundResult;
import com.bingtangcode.core.SystemReminderManager;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.permission.PermissionMode;
import com.bingtangcode.permission.PermissionModeProvider;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentLoop implements PermissionModeProvider {

    private final DialogueManager dialogue;
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final EventBus bus;
    private final int maxIterations;
    private final SystemReminderManager reminderManager;

    private final int maxStreamRetries;
    private final int maxPermissionDeniedStreak;

    private PermissionMode mode = PermissionMode.DEFAULT;
    private volatile boolean cancelled;
    private int unknownToolStreak;
    private int permissionDeniedStreak;

    // 接收 ConfigManager 的构造函数，方便主流程注入
    public AgentLoop(DialogueManager dialogue, LLMProvider provider,
                     ToolRegistry toolRegistry, EventBus bus, int maxIterations,
                     SystemReminderManager reminderManager, PermissionMode initialMode,
                     com.bingtangcode.config.ConfigManager config) {
        this(dialogue, provider, toolRegistry, bus, maxIterations, reminderManager, initialMode,
             config.getAgentMaxStreamRetries(), config.getAgentMaxPermissionDeniedStreak());
    }

    // 保留旧构造函数，保障测试兼容性
    public AgentLoop(DialogueManager dialogue, LLMProvider provider,
                     ToolRegistry toolRegistry, EventBus bus, int maxIterations,
                     SystemReminderManager reminderManager, PermissionMode initialMode) {
        this(dialogue, provider, toolRegistry, bus, maxIterations, reminderManager, initialMode,
             3, 5);
    }

    // 全参数构造函数
    public AgentLoop(DialogueManager dialogue, LLMProvider provider,
                     ToolRegistry toolRegistry, EventBus bus, int maxIterations,
                     SystemReminderManager reminderManager, PermissionMode initialMode,
                     int maxStreamRetries, int maxPermissionDeniedStreak) {
        this.dialogue = dialogue;
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.bus = bus;
        this.maxIterations = maxIterations;
        this.reminderManager = reminderManager;
        this.mode = initialMode;
        this.maxStreamRetries = maxStreamRetries;
        this.maxPermissionDeniedStreak = maxPermissionDeniedStreak;
    }

    public void run(String userInput) {
        dialogue.addUserMessage(userInput);

        AtomicInteger totalInputTokens = new AtomicInteger(0);
        AtomicInteger totalOutputTokens = new AtomicInteger(0);

        int iteration = 0;
        while (iteration < maxIterations) {
            if (cancelled) {
                bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.CANCELLED));
                return;
            }

            iteration++;
            long iterStart = System.currentTimeMillis();
            bus.fire(new AgentEvent.LoopIterationStarted(iteration));

            injectReminder();

            RoundResult result = runRoundWithRetry(totalInputTokens, totalOutputTokens);
            if (result == null) {
                if (cancelled) {
                    bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.CANCELLED));
                } else {
                    bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.STREAM_ERROR));
                }
                return;
            }

            bus.fire(new AgentEvent.LoopIterationEnded(iteration, System.currentTimeMillis() - iterStart));
            reminderManager.onRoundComplete();

            if (result.completed()) {
                bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.COMPLETED));
                return;
            }

            if (result.hasUnknown()) {
                unknownToolStreak++;
                if (unknownToolStreak >= 3) {
                    bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.UNKNOWN_TOOL_LOOP));
                    return;
                }
                continue;
            }

            unknownToolStreak = 0;

            if (result.allPermissionDenied()) {
                permissionDeniedStreak++;
                if (permissionDeniedStreak >= maxPermissionDeniedStreak) {
                    bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.PERMISSION_DENIED_LOOP));
                    return;
                }
            } else {
                permissionDeniedStreak = 0;
            }
        }

        bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.MAX_ITERATIONS));
    }

    private void injectReminder() {
        String reminder = reminderManager.getReminder();
        if (reminder != null) {
            dialogue.addMessage(new Message(Role.USER, reminder));
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public void setMode(PermissionMode mode) {
        this.mode = mode;
        reminderManager.onModeSwitch(mode);
    }

    public PermissionMode getMode() {
        return mode;
    }

    @Override
    public PermissionMode getCurrentMode() {
        return mode;
    }

    private List<Tool> selectTools() {
        List<Tool> all = toolRegistry.getAll();
        if (mode == PermissionMode.PLAN) {
            return all.stream().filter(Tool::isReadOnly).toList();
        }
        return all;
    }

    private RoundResult runRoundWithRetry(AtomicInteger totalInput, AtomicInteger totalOutput) {
        for (int attempt = 0; attempt <= maxStreamRetries; attempt++) {
            try {
                return dialogue.doRound(provider, selectTools(), bus, totalInput, totalOutput);
            } catch (Exception e) {
                if (cancelled || attempt >= maxStreamRetries) {
                    return null;
                }
                try {
                    Thread.sleep((1L << attempt) * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    public void manualCompress() {
        int currentEstimate = dialogue.estimateCurrentTokens();
        int threshold = dialogue.getContextWindow() - dialogue.getContextSummaryReserve() - dialogue.getContextManualCompressMargin();

        System.out.println("\n[系统] 当前估算 Token: " + currentEstimate + "，手动压缩阈值: " + threshold);
        if (currentEstimate < threshold) {
            System.out.println("[系统] 当前估算 Token 未达到手动压缩阈值，但将强行执行压缩...");
        }

        try {
            dialogue.compressHistory(provider, true);
        } catch (Exception e) {
            System.err.println("[系统错误] 手动历史压缩失败: " + e.getMessage());
        }
    }
}
