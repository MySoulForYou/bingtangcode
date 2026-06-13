package com.bingtangcode.permission;

/** 动态获取当前 PermissionMode 的接口，由 AgentLoop 提供。 */
public interface PermissionModeProvider {
    PermissionMode getCurrentMode();
}
