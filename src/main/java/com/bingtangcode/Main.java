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
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.LLMimpl.AnthropicProvider;
import com.bingtangcode.llm.LLMimpl.OpenAIProvider;
import com.bingtangcode.core.SessionPersister;
import com.bingtangcode.core.SessionSerializer;
import com.bingtangcode.core.SessionRecovery;
import com.bingtangcode.permission.Blacklist;
import com.bingtangcode.permission.PermissionConfigLoader;
import com.bingtangcode.permission.PermissionConfigManager;
import com.bingtangcode.permission.PermissionGate;
import com.bingtangcode.permission.PermissionMode;
import com.bingtangcode.permission.PermissionPrompt;
import com.bingtangcode.permission.RuleEngine;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolExecutor;
import com.bingtangcode.tool.ToolRegistry;
import com.bingtangcode.tool.tools.*;
import com.bingtangcode.tui.TerminalIO;
import com.bingtangcode.tui.TuiEventListener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
            // 不再需要 Bash 工具的 confirmCommand hook，PermissionGate 统一处理
            toolRegistry.register(new ExecuteCommandTool(projectRoot, null));
            toolRegistry.register(new FindFilesTool(projectRoot, config.getToolFindFilesMaxResults()));
            toolRegistry.register(new SearchContentTool(projectRoot));

            // --- MCP 客户端装配 ---
            com.bingtangcode.mcp.McpManager mcpManager = com.bingtangcode.mcp.McpManager.start(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    mcpManager.close();
                } catch (Exception ignored) {}
            }, "mcp-cleanup"));

            for (Tool mcpTool : mcpManager.getTools()) {
                toolRegistry.register(mcpTool);
            }

            ToolExecutor toolExecutor = new ToolExecutor(config.getToolTimeoutSeconds());
            List<Tool> toolList = toolRegistry.getAll();

            System.out.println("已注册 " + toolList.size() + " 个工具: "
                    + toolList.stream().map(Tool::getName).toList());

            // --- 权限系统装配 ---
            PermissionConfigLoader configLoader = new PermissionConfigLoader(projectRoot);
            PermissionConfigManager configManager = new PermissionConfigManager(projectRoot, configLoader);
            PermissionMode initialMode = configManager.loadDefaultMode();
            RuleEngine ruleEngine = new RuleEngine(configLoader);
            Blacklist blacklist = new Blacklist();
            PermissionPrompt permissionPrompt = new PermissionPrompt(terminalIO);

            // PermissionGate 在 AgentLoop 之后构造（需要 AgentLoop 作为 modeProvider）
            // 先占位，构造完整后再设置

            SessionPersister persister = new SessionPersister(projectRoot);
            persister.startAsyncCleanup();

            SystemPromptBuilder promptBuilder = new SystemPromptBuilder();
            EnvInfo envInfo = buildEnvInfo(projectRoot);
            String basePrompt = promptBuilder.build(providerName, modelName, envInfo);

            SystemReminderManager reminderManager = new SystemReminderManager();
            reminderManager.loadInstructions(projectRoot);
            String projectInstructions = reminderManager.getLoadedInstructions();

            String projectMemoryIndex = "";
            java.nio.file.Path projectIndexFile = projectRoot.resolve(".bingtangcode").resolve("memory").resolve("MEMORY.md");
            if (java.nio.file.Files.exists(projectIndexFile)) {
                try {
                    projectMemoryIndex = java.nio.file.Files.readString(projectIndexFile, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }

            String userMemoryIndex = "";
            java.nio.file.Path userIndexFile = java.nio.file.Paths.get(System.getProperty("user.home")).resolve(".bingtangcode").resolve("memory").resolve("MEMORY.md");
            if (java.nio.file.Files.exists(userIndexFile)) {
                try {
                    userMemoryIndex = java.nio.file.Files.readString(userIndexFile, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }

            StringBuilder promptSb = new StringBuilder(basePrompt);
            if (projectInstructions != null && !projectInstructions.isEmpty()) {
                promptSb.append("\n\n<project-instructions>\n").append(projectInstructions).append("\n</project-instructions>");
            }
            if (!projectMemoryIndex.isEmpty() || !userMemoryIndex.isEmpty()) {
                promptSb.append("\n\n<long-term-memory>\n");
                if (!projectMemoryIndex.isEmpty()) {
                    promptSb.append("【项目级记忆索引】\n").append(projectMemoryIndex).append("\n");
                }
                if (!userMemoryIndex.isEmpty()) {
                    promptSb.append("【用户级记忆索引】\n").append(userMemoryIndex).append("\n");
                }
                promptSb.append("</long-term-memory>");
            }
            String combinedSystemPrompt = promptSb.toString();

            List<SessionPersister.SessionMeta> existingSessions = persister.listSessions();
            String sessionId = null;
            List<Message> restoredHistory = null;

            if (!existingSessions.isEmpty()) {
                System.out.println("发现历史会话记录：");
                System.out.println("  [0] 新建会话 (Start a new session)");
                for (int i = 0; i < existingSessions.size(); i++) {
                    SessionPersister.SessionMeta meta = existingSessions.get(i);
                    System.out.printf("  [%d] %s (最后活跃: %s, 消息数: %d) - %s\n",
                            i + 1, meta.id,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(meta.lastActiveAt),
                            meta.messageCount, meta.title);
                }
                System.out.print("请选择要载入的会话编号 (输入数字，默认 0 为新建): ");
                String choice = terminalIO.readLine("> ").trim();
                int choiceIdx = 0;
                try {
                    if (!choice.isEmpty()) {
                        choiceIdx = Integer.parseInt(choice);
                    }
                } catch (NumberFormatException ignored) {}

                if (choiceIdx > 0 && choiceIdx <= existingSessions.size()) {
                    SessionPersister.SessionMeta selected = existingSessions.get(choiceIdx - 1);
                    sessionId = selected.id;
                    System.out.println("正在恢复会话: " + sessionId + "...");
                    try {
                        List<SessionSerializer.JSONLRecord> records = persister.loadSessionRecords(sessionId);
                        DialogueManager tempDm = new DialogueManager(
                                combinedSystemPrompt, toolRegistry, toolExecutor, null, toolList,
                                sessionId, config);
                        restoredHistory = SessionRecovery.recoverSession(sessionId, records, tempDm, provider);
                        System.out.println("会话恢复成功，已加载 " + restoredHistory.size() + " 条消息。");
                    } catch (Exception e) {
                        System.err.println("会话恢复失败，将启动新会话: " + e.getMessage());
                        sessionId = null;
                        restoredHistory = null;
                    }
                }
            }

            if (sessionId == null) {
                String datePart = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                String randPart = String.format("%04x", new java.util.Random().nextInt(0x10000));
                sessionId = datePart + "-" + randPart;
                System.out.println("已启动全新会话: " + sessionId);
            }

            DialogueManager dialogue = new DialogueManager(
                    combinedSystemPrompt,
                    toolRegistry, toolExecutor, null, toolList,
                    sessionId, config);

            if (restoredHistory != null) {
                if (!restoredHistory.isEmpty() && restoredHistory.get(0).role() == Role.SYSTEM) {
                    restoredHistory.set(0, new Message(Role.SYSTEM, combinedSystemPrompt));
                }
                dialogue.restoreHistory(restoredHistory);
            }

            // --- 事件总线 ---
            EventBus eventBus = new EventBus();
            eventBus.subscribe(new TuiEventListener(terminalIO));

            // --- Agent Loop ---
            AgentLoop agentLoop = new AgentLoop(
                    dialogue, provider, toolRegistry,
                    eventBus, config.getMaxIterations(), reminderManager, initialMode,
                    config);

            // --- PermissionGate（依赖 AgentLoop 作为 modeProvider） ---
            PermissionGate permissionGate = new PermissionGate(
                    ruleEngine, blacklist, agentLoop, permissionPrompt,
                    configManager, toolRegistry, projectRoot);
            permissionGate.setMode(initialMode);

            // 回注 permissionGate 到 DialogueManager
            dialogue.setPermissionGate(permissionGate);

            // --- 启动 ---
            SessionManager session = new SessionManager(
                    terminalIO, agentLoop, permissionGate, cancelAction);
            session.start();

            // --- 清理 ---
            provider.shutdown();
            toolExecutor.shutdown();
            terminalIO.shutdown();
        } catch (Exception e) {
            System.err.println("程序异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
