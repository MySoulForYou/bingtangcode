package com.bingtangcode.permission;

import com.bingtangcode.tool.ToolCall;

/** 人在回路处理器接口，由 PermissionPrompt 实现。 */
public interface HumanInTheLoopHandler {
    AskResult ask(ToolCall tc, PermissionMode mode);
}
