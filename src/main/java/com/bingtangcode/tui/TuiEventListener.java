package com.bingtangcode.tui;

import com.bingtangcode.agent.AgentEvent;
import com.bingtangcode.agent.AgentEventListener;

public class TuiEventListener implements AgentEventListener {

    private static final String GRAY = "\033[90m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private final TerminalIO terminalIO;

    public TuiEventListener(TerminalIO terminalIO) {
        this.terminalIO = terminalIO;
    }

    @Override
    public void onTextDelta(AgentEvent.TextDelta event) {
        terminalIO.printToken(event.token());
    }

    @Override
    public void onReasoningDelta(AgentEvent.ReasoningDelta event) {
        terminalIO.countToken(event.token());
        System.out.print(GRAY + event.token() + RESET);
        System.out.flush();
    }

    @Override
    public void onToolCallStarted(AgentEvent.ToolCallStarted event) {
    }

    @Override
    public void onToolCallCompleted(AgentEvent.ToolCallCompleted event) {
        String status = event.isError()
                ? " " + RED + "✗" + RESET
                : " " + GREEN + "✓" + RESET;
        System.out.println(GRAY + "  " + event.toolName() + RESET + status
                + GRAY + " " + event.elapsedMs() + "ms" + RESET);
    }

    @Override
    public void onTokenUsage(AgentEvent.TokenUsage event) {
        terminalIO.setTotalTokens(event.totalInput() + event.totalOutput());
        System.out.println();
        System.out.println(GRAY + "  ↑" + fmt(event.inputTokens())
                + " ↓" + fmt(event.outputTokens())
                + " · 累计 " + fmt(event.totalInput() + event.totalOutput()) + RESET);
    }

    @Override
    public void onAgentFinished(AgentEvent.AgentFinished event) {
        String reason = event.stopReason();
        if (AgentEvent.AgentFinished.MAX_ITERATIONS.equals(reason)) {
            System.out.println(GRAY + "  ⚠ 达到最大迭代次数，已停止" + RESET);
        } else if (AgentEvent.AgentFinished.CANCELLED.equals(reason)) {
            terminalIO.printInterrupted();
        } else if (AgentEvent.AgentFinished.UNKNOWN_TOOL_LOOP.equals(reason)) {
            System.out.println(GRAY + "  ⚠ 连续调用未知工具，已停止" + RESET);
        } else if (AgentEvent.AgentFinished.STREAM_ERROR.equals(reason)) {
            System.out.println(GRAY + "  ⚠ 网络请求失败，已重试3次仍无法恢复" + RESET);
        }
    }

    private static String fmt(int tokens) {
        if (tokens >= 1000) return String.format("%.1fk", tokens / 1000.0);
        return String.valueOf(tokens);
    }
}
