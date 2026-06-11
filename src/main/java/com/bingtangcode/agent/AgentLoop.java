package com.bingtangcode.agent;

import com.bingtangcode.core.DialogueManager;
import com.bingtangcode.core.RoundResult;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentLoop {

    private static final int MAX_STREAM_RETRIES = 3;

    public enum Mode { PLAN, FULL }

    private final DialogueManager dialogue;
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final EventBus bus;
    private final int maxIterations;

    private Mode mode = Mode.FULL;
    private volatile boolean cancelled;
    private int unknownToolStreak;

    public AgentLoop(DialogueManager dialogue, LLMProvider provider,
                     ToolRegistry toolRegistry, EventBus bus, int maxIterations) {
        this.dialogue = dialogue;
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.bus = bus;
        this.maxIterations = maxIterations;
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

            RoundResult result = runRoundWithRetry(totalInputTokens, totalOutputTokens);
            if (result == null) {
                bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.STREAM_ERROR));
                return;
            }

            bus.fire(new AgentEvent.LoopIterationEnded(iteration, System.currentTimeMillis() - iterStart));

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
        }

        bus.fire(new AgentEvent.AgentFinished(AgentEvent.AgentFinished.MAX_ITERATIONS));
    }

    public void cancel() {
        cancelled = true;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    private List<Tool> selectTools() {
        List<Tool> all = toolRegistry.getAll();
        if (mode == Mode.PLAN) {
            return all.stream().filter(Tool::isReadOnly).toList();
        }
        return all;
    }

    private RoundResult runRoundWithRetry(AtomicInteger totalInput, AtomicInteger totalOutput) {
        for (int attempt = 0; attempt <= MAX_STREAM_RETRIES; attempt++) {
            try {
                return dialogue.doRound(provider, selectTools(), bus, totalInput, totalOutput);
            } catch (Exception e) {
                if (cancelled || attempt >= MAX_STREAM_RETRIES) {
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
}
