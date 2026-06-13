package com.bingtangcode.agent;

/**
 * Agent Loop 运行过程中发出的事件。
 * 由 AgentLoop 通过 EventBus 广播，订阅者（TUI、日志、测试等）自行消费。
 * 事件不参与 Agent Loop 内部逻辑——Agent Loop 不依赖订阅者的处理结果。
 */
public sealed interface AgentEvent {

    /** 每轮循环开始时触发 */
    record LoopIterationStarted(int iteration) implements AgentEvent {}

    /** 模型输出推理/思考内容时逐 token 触发 */
    record ReasoningDelta(String token) implements AgentEvent {}

    /** 模型输出可见文本时逐 token 触发 */
    record TextDelta(String token) implements AgentEvent {}

    /** 开始执行一个工具 */
    record ToolCallStarted(String toolName, String toolCallId) implements AgentEvent {}

    /** 一个工具执行完毕 */
    record ToolCallCompleted(String toolName, String toolCallId, boolean isError, long elapsedMs)
            implements AgentEvent {}

    /** LLM 请求完成后的 token 用量。同时包含本轮和累计值 */
    record TokenUsage(int inputTokens, int outputTokens, int totalInput, int totalOutput)
            implements AgentEvent {}

    /** 每轮循环结束时触发 */
    record LoopIterationEnded(int iteration, long elapsedMs) implements AgentEvent {}

    /** Agent Loop 终止 */
    record AgentFinished(String stopReason) implements AgentEvent {
        public static final String COMPLETED = "COMPLETED";
        public static final String MAX_ITERATIONS = "MAX_ITERATIONS";
        public static final String CANCELLED = "CANCELLED";
        public static final String UNKNOWN_TOOL_LOOP = "UNKNOWN_TOOL_LOOP";
        public static final String PERMISSION_DENIED_LOOP = "PERMISSION_DENIED_LOOP";
        public static final String STREAM_ERROR = "STREAM_ERROR";
    }
}
