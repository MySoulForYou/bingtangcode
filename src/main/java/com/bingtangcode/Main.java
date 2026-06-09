package com.bingtangcode;

import com.bingtangcode.config.ConfigManager;
import com.bingtangcode.core.DialogueManager;
import com.bingtangcode.core.SessionManager;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.LLMimpl.AnthropicProvider;
import com.bingtangcode.llm.LLMimpl.OpenAIProvider;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolExecutor;
import com.bingtangcode.tool.ToolRegistry;
import com.bingtangcode.tool.tools.*;
import com.bingtangcode.tui.TerminalIO;

import java.nio.file.Path;
import java.util.List;

public class Main {

    private static String buildSystemPrompt(String providerName, String modelName) {
        return "你是 bingtangCode，一个终端 AI 编程助手。底层由 " + providerName
                + " 驱动，当前模型为 " + modelName + "。请帮助用户解决编程问题，回答使用中文。";
    }

    public static void main(String[] args) {
        try {
            ConfigManager config = new ConfigManager();
            Path projectRoot = Path.of("").toAbsolutePath();

            LLMProvider provider;
            Runnable cancelAction;

            String providerName = config.getProvider();
            String modelName;
            if ("anthropic".equals(providerName)) {
                modelName = config.getAnthropicModel();
                AnthropicProvider ap = new AnthropicProvider(
                        config.getAnthropicApiKey(),
                        modelName,
                        config.getAnthropicEndpoint(),
                        config.getMaxTokens());
                provider = ap;
                cancelAction = ap::cancel;
            } else if ("openai".equals(providerName)) {
                modelName = config.getOpenAiModel();
                OpenAIProvider op = new OpenAIProvider(
                        config.getOpenAiApiKey(),
                        modelName,
                        config.getOpenAiEndpoint(),
                        config.getMaxTokens());
                provider = op;
                cancelAction = op::cancel;
            } else {
                System.err.println("不支持的 provider: " + providerName
                        + "，支持: anthropic, openai");
                System.exit(1);
                return;
            }

            TerminalIO terminalIO = new TerminalIO();
            terminalIO.setModelName(modelName);

            // --- 工具系统装配 ---
            // 1. 注册中心 + 六个工具
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.register(new ReadFileTool(projectRoot));
            toolRegistry.register(new WriteFileTool(projectRoot));
            toolRegistry.register(new EditFileTool(projectRoot));
            // ExecuteCommandTool 注入确认回调——执行前弹出 TUI 确认框
            toolRegistry.register(new ExecuteCommandTool(projectRoot, terminalIO::confirmCommand));
            toolRegistry.register(new FindFilesTool(projectRoot));
            toolRegistry.register(new SearchContentTool(projectRoot));

            // 2. 工具执行器（超时控制）
            ToolExecutor toolExecutor = new ToolExecutor(config.getToolTimeoutSeconds());
            List<Tool> toolList = toolRegistry.getAll();

            System.out.println("已注册 " + toolList.size() + " 个工具: "
                    + toolList.stream().map(Tool::getName).toList());

            // 3. 对话管理器注入工具能力
            DialogueManager dialogue = new DialogueManager(
                    buildSystemPrompt(providerName, modelName),
                    toolRegistry, toolExecutor, toolList);

            // --- 启动 ---
            SessionManager session = new SessionManager(
                    terminalIO, dialogue, provider, cancelAction);
            session.start();

            // --- 清理 ---
            provider.shutdown();
            toolExecutor.shutdown();
            terminalIO.shutdown();
        } catch (Exception e) {
            System.err.println("程序异常: " + e.getMessage());
            System.exit(1);
        }
    }
}
