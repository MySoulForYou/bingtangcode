package com.bingtangcode.permission;

/** 权限检查结果。allowed=false 表示拒绝，allowed=true 表示放行。null 返回值表示"未决定"。 */
public record PermissionResult(boolean allowed, String reason, String matchedRule) {

    public static PermissionResult allow() {
        return new PermissionResult(true, null, null);
    }

    public static PermissionResult deny(String reason, String matchedRule) {
        return new PermissionResult(false, reason, matchedRule);
    }
}
