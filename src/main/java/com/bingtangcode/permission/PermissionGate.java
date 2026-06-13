package com.bingtangcode.permission;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolRegistry;

import java.nio.file.Path;

/**
 * 五层防御的编排者。
 * 按顺序执行：黑名单 → 路径沙箱 → 规则引擎 → 模式兜底 → 人在回路。
 */
public class PermissionGate {

    private final RuleEngine ruleEngine;
    private final Blacklist blacklist;
    private final PermissionModeProvider modeProvider;
    private final HumanInTheLoopHandler hitlHandler;
    private final PermissionConfigManager configManager;
    private final ToolRegistry toolRegistry;
    private final Path projectRoot;

    private PermissionMode mode;

    public PermissionGate(RuleEngine ruleEngine, Blacklist blacklist,
                          PermissionModeProvider modeProvider,
                          HumanInTheLoopHandler hitlHandler,
                          PermissionConfigManager configManager,
                          ToolRegistry toolRegistry, Path projectRoot) {
        this.ruleEngine = ruleEngine;
        this.blacklist = blacklist;
        this.modeProvider = modeProvider;
        this.hitlHandler = hitlHandler;
        this.configManager = configManager;
        this.toolRegistry = toolRegistry;
        this.projectRoot = projectRoot;
        this.mode = PermissionMode.DEFAULT;
    }

    public void setMode(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionMode getMode() {
        return mode;
    }

    /**
     * 五层防御检查。返回 ALLOW 则执行，DENY 则返回结构化错误。
     */
    public PermissionResult check(ToolCall tc) {
        String internalName = tc.name();
        Tool tool = toolRegistry.get(internalName);
        boolean isBash = "execute_command".equals(internalName);
        boolean isReadOnly = tool != null && tool.isReadOnly();

        // Layer 1: 黑名单（仅对 Bash，不可绕过）
        if (isBash) {
            String command = ToolFriendlyName.extractMainParam(internalName, tc.parameters());
            PermissionResult blackResult = blacklist.check(command);
            if (!blackResult.allowed()) {
                return blackResult;
            }
        }

        // Layer 2: 路径沙箱（仅对文件类工具，不可绕过）
        if (isFileTool(internalName)) {
            try {
                String pathParam = ToolFriendlyName.extractMainParam(internalName, tc.parameters());
                if (pathParam != null) {
                    PathSandbox.validate(projectRoot, pathParam);
                }
            } catch (PathViolationException e) {
                return PermissionResult.deny(e.getMessage(), "path-sandbox");
            }
        }

        // Layer 3: 规则引擎（所有模式均生效，包括 BYPASS）
        PermissionResult ruleResult = ruleEngine.check(internalName, tc.parameters(), projectRoot);
        if (ruleResult != null) {
            return ruleResult;
        }

        // Layer 4: 模式兜底
        PermissionMode currentMode = modeProvider.getCurrentMode();

        if (currentMode == PermissionMode.BYPASS_PERMISSIONS) {
            return PermissionResult.allow();
        }

        PermissionMode.DefaultAction defaultAction = currentMode.getDefaultAction(isReadOnly, isBash);
        if (defaultAction == PermissionMode.DefaultAction.ALLOW) {
            return PermissionResult.allow();
        }

        // Layer 5: 人在回路
        if (hitlHandler != null) {
            AskResult askResult = hitlHandler.ask(tc, currentMode);
            return switch (askResult) {
                case ALLOW_ONCE -> PermissionResult.allow();
                case ALLOW_FOREVER -> {
                    PermissionRule foreverRule = new PermissionRule(
                            ToolFriendlyName.friendlyName(internalName),
                            extractPattern(internalName, tc),
                            PermissionAction.ALLOW,
                            "local");
                    configManager.appendRuleToLocal(foreverRule);
                    yield PermissionResult.allow();
                }
                case DENY_ONCE -> PermissionResult.deny(
                        "用户拒绝执行",
                        "human-in-the-loop");
            };
        }

        return PermissionResult.deny("未命中规则且无人确认", "default");
    }

    // ==================== private ====================

    private boolean isFileTool(String internalName) {
        return switch (internalName) {
            case "read_file", "write_file", "edit_file", "find_files", "search_content" -> true;
            default -> false;
        };
    }

    private String extractPattern(String internalName, ToolCall tc) {
        String param = ToolFriendlyName.extractMainParam(internalName, tc.parameters());
        return param != null ? param : "*";
    }
}
