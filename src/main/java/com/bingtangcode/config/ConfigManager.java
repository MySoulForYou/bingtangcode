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
    @SuppressWarnings("unchecked")
    public ConfigManager() {
        if (!Files.exists(CONFIG_PATH)) {
            printGuideAndExit();
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            root = mapper.readValue(in, Map.class);
        } catch (IOException e) {
            System.err.println("错误: 无法解析配置文件 " + CONFIG_PATH.toAbsolutePath() + ": " + e.getMessage());
            System.exit(1);
            throw new RuntimeException("unreachable");
        }

        this.provider = getString(root, "provider", "");

        Map<String, Object> anthropic = getMap(root, "anthropic");
        this.anthropicApiKey = getString(anthropic, "api_key", "");
        this.anthropicModel = getString(anthropic, "model", "claude-opus-4-7");
        this.anthropicEndpoint = getString(anthropic, "endpoint", DEFAULT_ANTHROPIC_ENDPOINT);

        Map<String, Object> openai = getMap(root, "openai");
        this.openAiApiKey = getString(openai, "api_key", "");
        this.openAiModel = getString(openai, "model", "gpt-4o");
        this.openAiEndpoint = getString(openai, "endpoint", DEFAULT_OPENAI_ENDPOINT);

        this.maxTokens = getInt(root, "max_tokens", DEFAULT_MAX_TOKENS);

        Map<String, Object> tool = getMap(root, "tool");
        this.toolTimeout = getInt(tool, "timeout_seconds", DEFAULT_TOOL_TIMEOUT);

        Map<String, Object> agent = getMap(root, "agent");
        this.maxIterations = getInt(agent, "max_iterations", DEFAULT_MAX_ITERATIONS);

        this.showReasoning = getBool(root, "show_reasoning", DEFAULT_SHOW_REASONING);

        if ("anthropic".equals(provider) && anthropicApiKey.isBlank()) {
            System.err.println("错误: 请在 " + CONFIG_PATH.toAbsolutePath() + " 中设置 anthropic.api_key");
            System.exit(1);
        }
        if ("openai".equals(provider) && openAiApiKey.isBlank()) {
            System.err.println("错误: 请在 " + CONFIG_PATH.toAbsolutePath() + " 中设置 openai.api_key");
            System.exit(1);
        }
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
