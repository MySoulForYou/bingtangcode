package com.bingtangcode.core;

import com.bingtangcode.tool.ToolCall;

import java.util.List;

public record RoundResult(boolean completed, List<ToolCall> toolCalls,
                          String textContent, boolean hasUnknown) {
    public static final RoundResult COMPLETED = new RoundResult(true, List.of(), "", false);
}
