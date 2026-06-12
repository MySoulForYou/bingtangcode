package com.bingtangcode;

import com.bingtangcode.agent.AgentLoop;
import com.bingtangcode.agent.EventBus;
import com.bingtangcode.config.ConfigManager;
import com.bingtangcode.core.DialogueManager;
import com.bingtangcode.core.SessionManager;
import com.bingtangcode.core.SystemPromptBuilder;
import com.bingtangcode.core.SystemPromptBuilder.EnvInfo;
import com.bingtangcode.core.SystemReminderManager;
import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.LLMimpl.AnthropicProvider;
import com.bingtangcode.llm.LLMimpl.OpenAIProvider;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolExecutor;
import com.bingtangcode.tool.ToolRegistry;
import com.bingtangcode.tool.tools.*;
import com.bingtangcode.tui.TerminalIO;
import com.bingtangcode.tui.TuiEventListener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class Main {

    private static String loadVersion() {
        try (InputStream in = Main.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank() && !v.startsWith("$")) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0";
    }

    private static EnvInfo buildEnvInfo(Path projectRoot) {
        String workDir = projectRoot.toAbsolutePath().toString();
        String platform = System.getProperty("os.name", "未知");
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "未知";
        }
        String osVersion = System.getProperty("os.version", "未知");
        String date = LocalDate.now().toString();
        String gitStatus = getGitStatus(projectRoot);
        return new EnvInfo(workDir, platform, shell, osVersion, date, gitStatus);
    }

    private static String getGitStatus(Path projectRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String result = reader.lines().collect(Collectors.joining("\n"));
                process.waitFor();
                if (process.exitValue() == 0) {
                    return result;
                }
            }
        } catch (Exception ignored) {
        }
        return "未知";
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
                        config.getMaxTokens(),
                        config.getShowReasoning());
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
            terminalIO.setVersion(loadVersion());

            // --- 工具系统装配 ---
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.register(new ReadFileTool(projectRoot));
            toolRegistry.register(new WriteFileTool(projectRoot));
            toolRegistry.register(new EditFileTool(projectRoot));
            toolRegistry.register(new ExecuteCommandTool(projectRoot, terminalIO::confirmCommand));
            toolRegistry.register(new FindFilesTool(projectRoot));
            toolRegistry.register(new SearchContentTool(projectRoot));

            ToolExecutor toolExecutor = new ToolExecutor(config.getToolTimeoutSeconds());
            List<Tool> toolList = toolRegistry.getAll();

            System.out.println("已注册 " + toolList.size() + " 个工具: "
                    + toolList.stream().map(Tool::getName).toList());

            // --- 对话管理器 ---
            SystemPromptBuilder promptBuilder = new SystemPromptBuilder();
            EnvInfo envInfo = buildEnvInfo(projectRoot);
            DialogueManager dialogue = new DialogueManager(
                    promptBuilder.build(providerName, modelName, envInfo),
                    toolRegistry, toolExecutor, toolList);

            // --- 事件总线 ---
            EventBus eventBus = new EventBus();
            eventBus.subscribe(new TuiEventListener(terminalIO));

            // --- Agent Loop ---
            SystemReminderManager reminderManager = new SystemReminderManager();
            AgentLoop agentLoop = new AgentLoop(
                    dialogue, provider, toolRegistry,
                    eventBus, config.getMaxIterations(), reminderManager);

            // --- 启动 ---
            SessionManager session = new SessionManager(
                    terminalIO, agentLoop, cancelAction);
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
