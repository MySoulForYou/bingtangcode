package com.bingtangcode.permission;

/** 路径越界异常，由 PathSandbox 抛出，PermissionGate 或工具自身捕获。 */
public class PathViolationException extends RuntimeException {
    public PathViolationException(String message) {
        super(message);
    }
}
