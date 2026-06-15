package com.bingtangcode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ConfigManager {

    private static final Path CONFIG_PATH = Paths.get("config.yaml");

    private static final String DEFAULT_ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final int DEFAULT_MAX_TOKENS = 32768;
    private static final int DEFAULT_TOOL_TIMEOUT = 30;
    private static final int DEFAULT_MAX_ITERATIONS = 20;
    private static final boolean DEFAULT_SHOW_REASONING = true;

    private final String provider;
    private final String anthropicApiKey;
    private final String anthropicModel;
    private final String anthropicEndpoint;
    private final String openAiApiKey;
    private final String openAiModel;
    private final String openAiEndpoint;
    private final int maxTokens;
    private final int toolTimeout;
    private final int maxIterations;
    private final boolean showReasoning;
    private final Map<String, McpServerConfig> mcpServers = new java.util.LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public ConfigManager() {
        Path userConfigPath = Paths.get(System.getProperty("user.home"), ".bingtangcode", "config.yaml");
        boolean userExists = Files.exists(userConfigPath);
        boolean projectExists = Files.exists(CONFIG_PATH);

        if (!userExists && !projectExists) {
            printGuideAndExit();
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> userRoot = Map.of();
        Map<String, Object> projectRoot = Map.of();

        if (userExists) {
            try (InputStream in = Files.newInputStream(userConfigPath)) {
                userRoot = mapper.readValue(in, Map.class);
            } catch (IOException e) {
                System.err.println("[警告] 无法解析用户全局配置文件 " + userConfigPath.toAbsolutePath() + ": " + e.getMessage());
            }
        }

        if (projectExists) {
            try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                projectRoot = mapper.readValue(in, Map.class);
            } catch (IOException e) {
                System.err.println("[警告] 无法解析项目配置文件 " + CONFIG_PATH.toAbsolutePath() + ": " + e.getMessage());
            }
        }

        // 深度合并配置：项目配置覆盖用户配置
        Map<String, Object> root = deepMerge(userRoot, projectRoot);

        this.provider = getString(root, "provider", "");

        Map<String, Object> anthropic = getMap(root, "anthropic");
        this.anthropicApiKey = EnvExpander.expand(getString(anthropic, "api_key", ""));
        this.anthropicModel = getString(anthropic, "model", "claude-opus-4-7");
        this.anthropicEndpoint = EnvExpander.expand(getString(anthropic, "endpoint", DEFAULT_ANTHROPIC_ENDPOINT));

        Map<String, Object> openai = getMap(root, "openai");
        this.openAiApiKey = EnvExpander.expand(getString(openai, "api_key", ""));
        this.openAiModel = getString(openai, "model", "gpt-4o");
        this.openAiEndpoint = EnvExpander.expand(getString(openai, "endpoint", DEFAULT_OPENAI_ENDPOINT));

        this.maxTokens = getInt(root, "max_tokens", DEFAULT_MAX_TOKENS);

        Map<String, Object> tool = getMap(root, "tool");
        this.toolTimeout = getInt(tool, "timeout_seconds", DEFAULT_TOOL_TIMEOUT);

        Map<String, Object> agent = getMap(root, "agent");
        this.maxIterations = getInt(agent, "max_iterations", DEFAULT_MAX_ITERATIONS);

        this.showReasoning = getBool(root, "show_reasoning", DEFAULT_SHOW_REASONING);

        if ("anthropic".equals(provider) && anthropicApiKey.isBlank()) {
            System.err.println("错误: 请设置 anthropic.api_key");
            System.exit(1);
        }
        if ("openai".equals(provider) && openAiApiKey.isBlank()) {
            System.err.println("错误: 请设置 openai.api_key");
            System.exit(1);
        }

        // 加载并合并 mcp_servers
        Map<String, McpServerConfig> userServers = loadMcpServers(userConfigPath);
        Map<String, McpServerConfig> projectServers = loadMcpServers(CONFIG_PATH);

        this.mcpServers.putAll(userServers);
        this.mcpServers.putAll(projectServers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        if (base == null) base = Map.of();
        if (override == null) override = Map.of();
        
        Map<String, Object> merged = new java.util.LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map && merged.get(key) instanceof Map) {
                merged.put(key, deepMerge((Map<String, Object>) merged.get(key), (Map<String, Object>) val));
            } else {
                merged.put(key, val);
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, McpServerConfig> loadMcpServers(Path path) {
        Map<String, McpServerConfig> parsedServers = new java.util.LinkedHashMap<>();
        if (!Files.exists(path)) {
            return parsedServers;
        }
        try (InputStream in = Files.newInputStream(path)) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = mapper.readValue(in, Map.class);
            if (root == null) return parsedServers;
            Object mcpServersObj = root.get("mcp_servers");
            if (mcpServersObj instanceof Map) {
                Map<String, Object> mcpMap = (Map<String, Object>) mcpServersObj;
                for (Map.Entry<String, Object> entry : mcpMap.entrySet()) {
                    String serverName = entry.getKey();
                    if (entry.getValue() instanceof Map) {
                        try {
                            McpServerConfig cfg = mapper.convertValue(entry.getValue(), McpServerConfig.class);
                            if (cfg == null) continue;

                            // 环境变量展开
                            if (cfg.getEnv() != null) {
                                java.util.Map<String, String> expandedEnv = new java.util.HashMap<>();
                                for (Map.Entry<String, String> envEntry : cfg.getEnv().entrySet()) {
                                    expandedEnv.put(envEntry.getKey(), EnvExpander.expand(envEntry.getValue()));
                                }
                                cfg.setEnv(expandedEnv);
                            }
                            if (cfg.getHeaders() != null) {
                                java.util.Map<String, String> expandedHeaders = new java.util.HashMap<>();
                                for (Map.Entry<String, String> headerEntry : cfg.getHeaders().entrySet()) {
                                    expandedHeaders.put(headerEntry.getKey(), EnvExpander.expand(headerEntry.getValue()));
                                }
                                cfg.setHeaders(expandedHeaders);
                            }

                            // 校验必填字段
                            String type = cfg.getType();
                            if (type == null) {
                                System.err.println("[错误] MCP Server " + serverName + " 缺少 type 字段");
                                continue;
                            }
                            type = type.toLowerCase();
                            cfg.setType(type);
                            if (!"stdio".equals(type) && !"http".equals(type)) {
                                System.err.println("[错误] MCP Server " + serverName + " 类型非法: " + type);
                                continue;
                            }
                            if ("stdio".equals(type)) {
                                if (cfg.getCommand() == null || cfg.getCommand().isBlank()) {
                                    System.err.println("[错误] MCP Server " + serverName + " (stdio) 缺少 command 字段");
                                    continue;
                                }
                            } else {
                                if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                                    System.err.println("[错误] MCP Server " + serverName + " (http) 缺少 url 字段");
                                    continue;
                                }
                            }

                            parsedServers.put(serverName, cfg);
                        } catch (Exception e) {
                            System.err.println("[警告] 无法解析 MCP Server " + serverName + " 的配置: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[警告] 无法读取配置文件 " + path + ": " + e.getMessage());
        }
        return parsedServers;
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    private void printGuideAndExit() {
        System.out.println("未找到配置文件: " + CONFIG_PATH.toAbsolutePath());
        System.out.println();
        System.out.println("可以从 config.example.yaml 复制一份作为起点:");
        System.out.println("  cp config.example.yaml config.yaml");
        System.out.println();
        System.out.println("然后填入你的 API Key:");
        System.out.println();
        System.out.println("  provider: anthropic");
        System.out.println("  # max_tokens: 32768  (可选，仅 Anthropic 生效)");
        System.out.println();
        System.out.println("  anthropic:");
        System.out.println("    api_key: sk-ant-xxxxx");
        System.out.println("    model: claude-opus-4-7");
        System.out.println("    # endpoint: https://api.anthropic.com/v1/messages  (可选)");
        System.out.println();
        System.out.println("  openai:");
        System.out.println("    api_key: sk-xxxxx");
        System.out.println("    model: gpt-4o");
        System.out.println("    # endpoint: https://api.openai.com/v1/chat/completions  (可选, 也支持 Ollama 等兼容接口)");
        System.out.println();
        System.out.println("provider 支持: anthropic, openai");
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
        Object value = parent != null ? parent.get(key) : null;
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map != null ? map.get(key) : null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map != null ? map.get(key) : null;
        return value != null ? value.toString().trim() : defaultValue;
    }

    public String getProvider() {
        return provider;
    }

    public String getAnthropicApiKey() {
        return anthropicApiKey;
    }

    public String getAnthropicModel() {
        return anthropicModel;
    }

    public String getAnthropicEndpoint() {
        return anthropicEndpoint;
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public String getOpenAiModel() {
        return openAiModel;
    }

    public String getOpenAiEndpoint() {
        return openAiEndpoint;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getToolTimeoutSeconds() {
        return toolTimeout;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public boolean getShowReasoning() {
        return showReasoning;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map != null ? map.get(key) : null;
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        return defaultValue;
    }
}
