package com.bingtangcode.permission;

import com.bingtangcode.tool.Tool;

public enum PermissionMode {
    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS_PERMISSIONS;

    public enum DefaultAction { ALLOW, ASK }

    /** 当前模式下该工具是否视为只读，用于模式兜底决策。 */
    public boolean isReadOnly(Tool tool) {
        return tool.isReadOnly();
    }

    /** 未命中任何规则时的默认行为。isBash 表示是否为 execute_command 工具。 */
    public DefaultAction getDefaultAction(boolean isReadOnly, boolean isBash) {
        return switch (this) {
            case DEFAULT -> isReadOnly ? DefaultAction.ALLOW : DefaultAction.ASK;
            case ACCEPT_EDITS -> isBash ? DefaultAction.ASK : DefaultAction.ALLOW;
            case PLAN -> isReadOnly ? DefaultAction.ALLOW : DefaultAction.ASK;
            case BYPASS_PERMISSIONS -> DefaultAction.ALLOW;
        };
    }
}
