package com.bingtangcode;

import com.bingtangcode.config.ConfigManager;
import com.bingtangcode.core.DialogueManager;
import com.bingtangcode.core.SessionManager;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.LLMimpl.AnthropicProvider;
import com.bingtangcode.llm.LLMimpl.OpenAIProvider;
import com.bingtangcode.tui.TerminalIO;

public class Main {

    private static String buildSystemPrompt(String providerName, String modelName) {
        return "你是 bingtangCode，一个终端 AI 编程助手。底层由 " + providerName
                + " 驱动，当前模型为 " + modelName + "。请帮助用户解决编程问题，回答使用中文。";
    }

    public static void main(String[] args) {
        try {
            ConfigManager config = new ConfigManager();

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

            DialogueManager dialogue = new DialogueManager(
                    buildSystemPrompt(providerName, modelName));
            TerminalIO terminalIO = new TerminalIO();
            terminalIO.setModelName(modelName);
            SessionManager session = new SessionManager(
                    terminalIO, dialogue, provider, cancelAction);
            session.start();

            provider.shutdown();
            terminalIO.shutdown();
        } catch (Exception e) {
            System.err.println("程序异常: " + e.getMessage());
            System.exit(1);
        }
    }
}
