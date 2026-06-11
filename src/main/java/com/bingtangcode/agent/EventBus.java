package com.bingtangcode.agent;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线——Agent Loop 和订阅者之间的中间人。
 *
 * 线程安全保障：内部用 CopyOnWriteArrayList。
 * fire() 同步调用所有监听器，单个监听器抛异常不影响其余监听器。
 * subscribe/unsubscribe 可在任意线程安全调用。
 */
public class EventBus {

    private final CopyOnWriteArrayList<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(AgentEventListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(AgentEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * 触发事件——按注册顺序同步通知所有监听器。
     * 监听器抛出的异常被静默捕获，不中断后续监听器也不抛给调用方。
     */
    public void fire(AgentEvent event) {
        for (AgentEventListener listener : listeners) {
            try {
                dispatch(listener, event);
            } catch (Exception ignored) {
            }
        }
    }

    /** 按事件类型分发到对应的 on* 方法 */
    private static void dispatch(AgentEventListener listener, AgentEvent event) {
        switch (event) {
            case AgentEvent.LoopIterationStarted e -> listener.onLoopIterationStarted(e);
            case AgentEvent.ReasoningDelta e       -> listener.onReasoningDelta(e);
            case AgentEvent.TextDelta e            -> listener.onTextDelta(e);
            case AgentEvent.ToolCallStarted e      -> listener.onToolCallStarted(e);
            case AgentEvent.ToolCallCompleted e    -> listener.onToolCallCompleted(e);
            case AgentEvent.TokenUsage e           -> listener.onTokenUsage(e);
            case AgentEvent.LoopIterationEnded e   -> listener.onLoopIterationEnded(e);
            case AgentEvent.AgentFinished e        -> listener.onAgentFinished(e);
        }
    }
}
