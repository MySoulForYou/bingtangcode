package com.bingtangcode.permission;

public record PermissionRule(String toolName, String pattern, PermissionAction action, String source) {
}
