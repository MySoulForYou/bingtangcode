package com.bingtangcode.agent;

/**
 * Agent 事件监听器。每个回调均有 default 空实现，
 * 订阅者只 override 自己关心的事件类型。
 */
public interface AgentEventListener {

    default void onLoopIterationStarted(AgentEvent.LoopIterationStarted event) {}

    default void onReasoningDelta(AgentEvent.ReasoningDelta event) {}

    default void onTextDelta(AgentEvent.TextDelta event) {}

    default void onToolCallStarted(AgentEvent.ToolCallStarted event) {}

    default void onToolCallCompleted(AgentEvent.ToolCallCompleted event) {}

    default void onTokenUsage(AgentEvent.TokenUsage event) {}

    default void onLoopIterationEnded(AgentEvent.LoopIterationEnded event) {}

    default void onAgentFinished(AgentEvent.AgentFinished event) {}
}
