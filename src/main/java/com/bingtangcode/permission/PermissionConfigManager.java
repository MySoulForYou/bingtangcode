package com.bingtangcode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 权限配置管理器：加载默认模式 + 持久化 ALLOW_FOREVER 规则。
 */
public class PermissionConfigManager {

    private final PermissionConfigLoader configLoader;
    private final Path projectRoot;

    public PermissionConfigManager(Path projectRoot, PermissionConfigLoader configLoader) {
        this.projectRoot = projectRoot;
        this.configLoader = configLoader;
    }

    public PermissionMode loadDefaultMode() {
        return configLoader.loadDefaultMode();
    }

    /**
     * 将一条 allow 规则追加写入 permissions.local.yaml。
     * 文件不存在时自动创建含 rules: 的空骨架。
     */
    public void appendRuleToLocal(PermissionRule rule) {
        Path dir = projectRoot.resolve(".bingtangcode");
        Path localFile = dir.resolve("permissions.local.yaml");

        try {
            Files.createDirectories(dir);
            if (!Files.exists(localFile)) {
                Files.writeString(localFile, "rules:\n", StandardOpenOption.CREATE);
            }

            String yamlEntry = String.format(
                    "  - tool: %s\n    pattern: \"%s\"\n    action: %s\n",
                    rule.toolName(), rule.pattern(), rule.action().name().toLowerCase());

            Files.writeString(localFile, yamlEntry,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("警告: 无法写入权限规则到 " + localFile + ": " + e.getMessage());
        }
    }

    public List<PermissionRule> loadMergedRules() {
        return configLoader.getMergedRules();
    }
}
