package com.bingtangcode.permission;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;

public class RuleEngine {

    private final PermissionConfigLoader configLoader;

    public RuleEngine(PermissionConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    /**
     * 检查工具调用是否命中规则。
     * 匹配优先级：local > project > user。
     * 所有层均无命中返回 null 表示"未决定"。
     */
    public PermissionResult check(String internalToolName, Map<String, Object> params,
                                   Path projectRoot) {
        String friendlyName = ToolFriendlyName.friendlyName(internalToolName);
        String mainParam = ToolFriendlyName.extractMainParam(internalToolName, params);
        String normalizedParam = normalizeParam(internalToolName, mainParam, projectRoot);

        PermissionResult result = matchLayer(configLoader.loadLocalRules(), friendlyName, normalizedParam);
        if (result != null) return result;

        result = matchLayer(configLoader.loadProjectRules(), friendlyName, normalizedParam);
        if (result != null) return result;

        result = matchLayer(configLoader.loadUserRules(), friendlyName, normalizedParam);
        return result;
    }

    // ==================== private ====================

    /**
     * 对文件类工具，将绝对路径转为项目相对路径用于规则匹配。
     * Bash 命令原样返回。
     */
    private String normalizeParam(String internalToolName, String mainParam, Path projectRoot) {
        if (mainParam == null) return null;
        if ("execute_command".equals(internalToolName)) return mainParam;

        try {
            Path abs = Path.of(mainParam);
            if (abs.isAbsolute()) {
                Path rel = projectRoot.toRealPath().relativize(abs);
                return rel.toString();
            }
        } catch (Exception ignored) {
        }
        return mainParam;
    }

    /**
     * 在单层规则中匹配。deny 优先；否则取书写顺序第一个 allow。无命中返回 null。
     */
    private PermissionResult matchLayer(List<PermissionRule> rules,
                                         String friendlyName, String mainParam) {
        if (mainParam == null) return null;

        PermissionRule firstAllow = null;
        for (PermissionRule rule : rules) {
            if (!rule.toolName().equals(friendlyName)) continue;
            if (!matchesPattern(rule.pattern(), mainParam)) continue;

            if (rule.action() == PermissionAction.DENY) {
                return PermissionResult.deny(
                        friendlyName + "(" + mainParam + ") 被 deny 规则拦截",
                        "[" + rule.source() + "] " + rule.toolName() + "(" + rule.pattern() + ") → deny");
            }
            if (firstAllow == null) {
                firstAllow = rule;
            }
        }
        if (firstAllow != null) {
            return PermissionResult.allow();
        }
        return null;
    }

    /** 含 * 或 ** 则 glob 匹配，否则精确字符串匹配。 */
    private boolean matchesPattern(String pattern, String value) {
        if (pattern.contains("*")) {
            try {
                PathMatcher matcher = FileSystems.getDefault()
                        .getPathMatcher("glob:" + pattern);
                return matcher.matches(Path.of(value));
            } catch (Exception e) {
                return false;
            }
        }
        return pattern.equals(value);
    }
}
