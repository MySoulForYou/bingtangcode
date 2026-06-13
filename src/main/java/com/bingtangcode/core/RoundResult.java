package com.bingtangcode.core;

import com.bingtangcode.tool.ToolCall;

import java.util.List;

public record RoundResult(boolean completed, List<ToolCall> toolCalls,
                          String textContent, boolean hasUnknown,
                          boolean allPermissionDenied) {

    public static final RoundResult COMPLETED =
            new RoundResult(true, List.of(), "", false, false);

    /** 无权限拒绝的构造 */
    public RoundResult(boolean completed, List<ToolCall> toolCalls,
                       String textContent, boolean hasUnknown) {
        this(completed, toolCalls, textContent, hasUnknown, false);
    }
}
