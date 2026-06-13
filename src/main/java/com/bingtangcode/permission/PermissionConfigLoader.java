package com.bingtangcode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PermissionConfigLoader {

    private static final Path USER_CONFIG_DIR = Paths.get(
            System.getProperty("user.home"), ".bingtangcode");
    private static final String USER_CONFIG = "permissions.yaml";
    private static final String PROJECT_CONFIG = "permissions.yaml";
    private static final String LOCAL_CONFIG = "permissions.local.yaml";

    private final Path projectRoot;
    private final ObjectMapper yamlMapper;

    public PermissionConfigLoader(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        ensureUserConfigExists();
    }

    /**
     * 首次启动时自动创建用户全局配置文件，含合理默认值。
     * 文件已存在则跳过，不覆盖用户已有的配置。
     */
    private void ensureUserConfigExists() {
        Path userConfigFile = USER_CONFIG_DIR.resolve(USER_CONFIG);
        if (Files.exists(userConfigFile)) {
            return;
        }
        try {
            Files.createDirectories(USER_CONFIG_DIR);
            Files.writeString(userConfigFile, DEFAULT_USER_CONFIG);
        } catch (IOException e) {
            System.err.println("警告: 无法创建用户权限配置文件 " + userConfigFile + ": " + e.getMessage());
        }
    }

    private static final String DEFAULT_USER_CONFIG = """
            # bingtangCode 用户全局权限配置（所有项目生效）
            # 优先级：项目本地 > 项目共享 > 用户全局
            #
            # defaultMode: default | acceptEdits | plan | bypassPermissions

            defaultMode: default

            rules:
              # ---- 安全底线（所有项目通用） ----

              # 禁止读取敏感配置文件
              - tool: Read
                pattern: ".env"
                action: deny
              - tool: Read
                pattern: ".env.*"
                action: deny
              - tool: Read
                pattern: "*.key"
                action: deny
              - tool: Read
                pattern: "*.pem"
                action: deny

              # ---- 常用命令放行（可按需调整） ----

              # Git 日常操作（force push main/master 仍被黑名单拦截）
              - tool: Bash
                pattern: "git *"
                action: allow

              # 构建工具
              - tool: Bash
                pattern: "mvn *"
                action: allow
              - tool: Bash
                pattern: "npm *"
                action: allow
              - tool: Bash
                pattern: "npx *"
                action: allow

              # 文件浏览
              - tool: Bash
                pattern: "ls *"
                action: allow
              - tool: Bash
                pattern: "cat *"
                action: allow
            """;

    /** 加载用户全局规则 ~/.bingtangcode/permissions.yaml */
    public List<PermissionRule> loadUserRules() {
        return loadRules(USER_CONFIG_DIR.resolve(USER_CONFIG), "user");
    }

    /** 加载项目规则 <project>/.bingtangcode/permissions.yaml */
    public List<PermissionRule> loadProjectRules() {
        return loadRules(projectRoot.resolve(".bingtangcode").resolve(PROJECT_CONFIG), "project");
    }

    /** 加载本地规则 <project>/.bingtangcode/permissions.local.yaml */
    public List<PermissionRule> loadLocalRules() {
        return loadRules(projectRoot.resolve(".bingtangcode").resolve(LOCAL_CONFIG), "local");
    }

    /**
     * 获取合并后的规则列表：user + project + local。
     * 高优先级放后面，RuleEngine 倒序遍历时逐层覆盖。
     */
    public List<PermissionRule> getMergedRules() {
        List<PermissionRule> merged = new ArrayList<>();
        merged.addAll(loadUserRules());
        merged.addAll(loadProjectRules());
        merged.addAll(loadLocalRules());
        return Collections.unmodifiableList(merged);
    }

    /**
     * 按 local > project > user 优先级取第一个存在的 defaultMode。
     * 都不存在返回 DEFAULT。
     */
    public PermissionMode loadDefaultMode() {
        PermissionMode mode;
        mode = loadDefaultModeFrom(projectRoot.resolve(".bingtangcode").resolve(LOCAL_CONFIG));
        if (mode != null) return mode;
        mode = loadDefaultModeFrom(projectRoot.resolve(".bingtangcode").resolve(PROJECT_CONFIG));
        if (mode != null) return mode;
        mode = loadDefaultModeFrom(USER_CONFIG_DIR.resolve(USER_CONFIG));
        if (mode != null) return mode;
        return PermissionMode.DEFAULT;
    }

    // ==================== private ====================

    @SuppressWarnings("unchecked")
    private List<PermissionRule> loadRules(Path configPath, String source) {
        Map<String, Object> root = readYaml(configPath);
        if (root == null) {
            return List.of();
        }
        try {
            Object rulesObj = root.get("rules");
            if (!(rulesObj instanceof List)) {
                return List.of();
            }
            List<Map<String, Object>> rawRules = (List<Map<String, Object>>) rulesObj;
            List<PermissionRule> rules = new ArrayList<>();
            for (Map<String, Object> entry : rawRules) {
                String tool = stringVal(entry, "tool");
                String pattern = stringVal(entry, "pattern");
                String actionStr = stringVal(entry, "action");
                if (tool == null || pattern == null || actionStr == null) {
                    continue;
                }
                PermissionAction action;
                try {
                    action = PermissionAction.valueOf(actionStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                rules.add(new PermissionRule(tool, pattern, action, source));
            }
            return Collections.unmodifiableList(rules);
        } catch (Exception e) {
            System.err.println("警告: 解析权限规则失败 " + configPath + ": " + e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            return yamlMapper.readValue(in, Map.class);
        } catch (IOException e) {
            System.err.println("警告: 无法读取权限配置文件 " + path + ": " + e.getMessage());
            return null;
        }
    }

    private PermissionMode loadDefaultModeFrom(Path path) {
        Map<String, Object> root = readYaml(path);
        if (root == null) return null;
        Object modeObj = root.get("defaultMode");
        if (!(modeObj instanceof String s)) return null;
        try {
            return PermissionMode.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("警告: 未知的 defaultMode 值 '" + s + "' 在 " + path);
            return null;
        }
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
