package com.bingtangcode.tool;

/**
 * 工具执行结果，与 Anthropic tool_result 格式对齐。
 * 工具自身返回此对象；ToolExecutor 负责注入 toolCallId 并在超时/异常时构造 isError=true 的结果。
 */
public record ToolResult(
        String toolCallId, String content, boolean isError) {
}
