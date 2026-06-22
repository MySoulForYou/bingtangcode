package com.bingtangcode.command;

import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.core.SessionPersister;
import com.bingtangcode.core.SessionSerializer;
import com.bingtangcode.core.SessionRecovery;
import com.bingtangcode.agent.AgentLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class BuiltInCommands {

    public static List<Command> createBuiltInCommands() {
        List<Command> cmds = new ArrayList<>();

        // 1. /exit
        cmds.add(new Command(
                "exit",
                List.of("quit", "q"),
                "退出程序",
                "/exit",
                CommandType.LOCAL,
                "",
                false,
                ctx -> {
                    ctx.getUi().addSystemMessage("正在退出...");
                    System.exit(0);
                }
        ));

        // 2. /plan
        cmds.add(new Command(
                "plan",
                List.of("p"),
                "进入 Plan 模式（仅可使用只读工具）",
                "/plan",
                CommandType.LOCAL_UI,
                "",
                false,
                ctx -> ctx.getUi().setPlanMode(true)
        ));

        // 3. /do
        cmds.add(new Command(
                "do",
                List.of("d"),
                "回到 Default 模式（可使用全部工具）",
                "/do",
                CommandType.LOCAL_UI,
                "",
                false,
                ctx -> ctx.getUi().setPlanMode(false)
        ));

        // 4. /compact
        cmds.add(new Command(
                "compact",
                List.of("c"),
                "手动触发历史消息压缩",
                "/compact",
                CommandType.LOCAL,
                "",
                false,
                ctx -> ctx.getAgentLoop().manualCompress()
        ));

        // 5. /resume
        cmds.add(new Command(
                "resume",
                List.of("r"),
                "选择并恢复历史会话",
                "/resume",
                CommandType.LOCAL_UI,
                "",
                false,
                ctx -> {
                    Path projectRoot = Paths.get("").toAbsolutePath();
                    SessionPersister persister = new SessionPersister(projectRoot);
                    List<SessionPersister.SessionMeta> existing = persister.listSessions();
                    if (existing.isEmpty()) {
                        ctx.getUi().addSystemMessage("无可用历史会话。");
                        return;
                    }
                    List<String> items = new ArrayList<>();
                    for (SessionPersister.SessionMeta meta : existing) {
                        items.add(meta.id + " (" + meta.title + ")");
                    }
                    int idx = ctx.getUi().selectFromList("选择历史会话", items, 0);
                    if (idx != -1) {
                        SessionPersister.SessionMeta selected = existing.get(idx);
                        String sid = selected.id;
                        ctx.getUi().addSystemMessage("正在恢复会话: " + sid + "...");
                        try {
                            List<SessionSerializer.JSONLRecord> records = persister.loadSessionRecords(sid);
                            com.bingtangcode.core.DialogueManager tempDm = new com.bingtangcode.core.DialogueManager(
                                    ctx.getDialogue().getHistory().isEmpty() ? "" : ctx.getDialogue().getHistory().get(0).content(),
                                    ctx.getDialogue().getToolRegistry(),
                                    null, null, ctx.getDialogue().getTools(), sid, ctx.getConfig()
                            );
                            List<Message> restored = SessionRecovery.recoverSession(
                                    sid, records, tempDm, ctx.getAgentLoop().getProvider()
                            );

                            if (!restored.isEmpty() && restored.get(0).role() == Role.SYSTEM) {
                                String currentSystemPrompt = ctx.getDialogue().getHistory().isEmpty() ? "" : ctx.getDialogue().getHistory().get(0).content();
                                restored.set(0, new Message(Role.SYSTEM, currentSystemPrompt));
                            }

                            ctx.getDialogue().switchSession(sid, restored);
                            ctx.getAgentLoop().resetSessionStats();
                            ctx.getUi().refreshStatus();
                            ctx.getUi().addSystemMessage("会话恢复成功，已加载 " + restored.size() + " 条消息。");
                        } catch (Exception e) {
                            ctx.getUi().addSystemMessage("会话恢复失败: " + e.getMessage());
                        }
                    }
                }
        ));

        // 6. /clear
        cmds.add(new Command(
                "clear",
                List.of(),
                "重置会话清空屏幕",
                "/clear",
                CommandType.LOCAL_UI,
                "",
                false,
                ctx -> {
                    String systemPrompt = ctx.getDialogue().getHistory().isEmpty() ? ""
                            : ctx.getDialogue().getHistory().get(0).content();
                    String datePart = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                    String randPart = String.format("%04x", new java.util.Random().nextInt(0x10000));
                    String newSessionId = datePart + "-" + randPart;

                    SessionPersister persister = new SessionPersister(Paths.get("").toAbsolutePath());
                    if (!systemPrompt.isEmpty()) {
                        Message sysMsg = new Message(Role.SYSTEM, systemPrompt);
                        persister.appendMessage(newSessionId, sysMsg);
                    }

                    List<Message> newHistory = new ArrayList<>();
                    if (!systemPrompt.isEmpty()) {
                        newHistory.add(new Message(Role.SYSTEM, systemPrompt));
                    }
                    ctx.getDialogue().switchSession(newSessionId, newHistory);
                    ctx.getAgentLoop().resetSessionStats();
                    ctx.getUi().clearScreen();
                    ctx.getUi().addSystemMessage("已成功开启新会话: " + newSessionId);
                }
        ));

        // 7. /help
        cmds.add(new Command(
                "help",
                List.of("h", "?"),
                "显示命令帮助",
                "/help",
                CommandType.LOCAL,
                "",
                false,
                ctx -> {
                    List<Command> sortedCmds = new ArrayList<>(ctx.getRegistry().getAllCommands());
                    Collections.sort(sortedCmds, (a, b) -> a.getName().compareTo(b.getName()));

                    int maxLineW = 40; // minimum visual width
                    List<String[]> formattedLines = new ArrayList<>();

                    for (Command cmd : sortedCmds) {
                        if (cmd.isHidden()) continue;
                        StringBuilder sb = new StringBuilder();
                        sb.append("/").append(cmd.getName());
                        if (!cmd.getAliases().isEmpty()) {
                            sb.append(" (").append(String.join(",", cmd.getAliases())).append(")");
                        }
                        String left = sb.toString();
                        String right = cmd.getDescription();
                        formattedLines.add(new String[]{left, right});

                        int lineW = 2 + 18 + 1 + visualWidth(right); // "  " + padRightVisual(left, 18) + " " + right
                        if (lineW > maxLineW) {
                            maxLineW = lineW;
                        }
                    }

                    int boxW = maxLineW + 2; // leave 2 spaces margin at the right end

                    ctx.getUi().addSystemMessage("");
                    ctx.getUi().addSystemMessage(String.format("╭%s╮", "─".repeat(boxW)));
                    ctx.getUi().addSystemMessage(String.format("│%s│", padRightVisual("  命令帮助", boxW)));
                    ctx.getUi().addSystemMessage(String.format("│%s│", " ".repeat(boxW)));

                    for (String[] line : formattedLines) {
                        String left = line[0];
                        String right = line[1];
                        String lineContent = "  " + padRightVisual(left, 18) + " " + right;
                        ctx.getUi().addSystemMessage("│" + padRightVisual(lineContent, boxW) + "│");
                    }

                    ctx.getUi().addSystemMessage(String.format("╰%s╯", "─".repeat(boxW)));
                    ctx.getUi().addSystemMessage("");
                }
        ));

        // 8. /status
        cmds.add(new Command(
                "status",
                List.of("s"),
                "显示系统运行状态",
                "/status",
                CommandType.LOCAL,
                "",
                false,
                ctx -> {
                    String modeStr = switch (ctx.getAgentLoop().getMode()) {
                        case PLAN -> "plan";
                        case DEFAULT -> "default";
                        case ACCEPT_EDITS -> "acceptEdits";
                        case BYPASS_PERMISSIONS -> "bypassPermissions";
                    };
                    String modelName = ctx.getConfig().getProvider().equals("anthropic")
                            ? ctx.getConfig().getAnthropicModel()
                            : ctx.getConfig().getOpenAiModel();
                    
                    ctx.getUi().addSystemMessage(String.format("%-10s: %s", "Mode", modeStr));
                    ctx.getUi().addSystemMessage(String.format("%-10s: in: %d / out: %d", "Tokens", ctx.getUi().getSessionInputTokens(), ctx.getUi().getSessionOutputTokens()));
                    ctx.getUi().addSystemMessage(String.format("%-10s: %d", "Tools", ctx.getAgentLoop().selectTools().size()));
                    ctx.getUi().addSystemMessage(String.format("%-10s: %d", "Memories", getLoadedMemoryFiles(Paths.get("").toAbsolutePath()).size()));
                    ctx.getUi().addSystemMessage(String.format("%-10s: %s", "Model", modelName));
                    ctx.getUi().addSystemMessage(String.format("%-10s: %s", "Directory", Paths.get("").toAbsolutePath().toString()));
                }
        ));

        // 9. /memory
        cmds.add(new Command(
                "memory",
                List.of("m"),
                "显示已加载记忆文件名列表",
                "/memory",
                CommandType.LOCAL,
                "",
                false,
                ctx -> {
                    List<String> files = getLoadedMemoryFiles(Paths.get("").toAbsolutePath());
                    if (files.isEmpty()) {
                        ctx.getUi().addSystemMessage("无已加载的记忆文件");
                    } else {
                        for (String f : files) {
                            ctx.getUi().addSystemMessage(f);
                        }
                    }
                }
        ));

        // 10. /permission
        cmds.add(new Command(
                "permission",
                List.of(),
                "显示当前权限模式",
                "/permission",
                CommandType.LOCAL,
                "",
                false,
                ctx -> {
                    String modeStr = switch (ctx.getAgentLoop().getMode()) {
                        case PLAN -> "plan";
                        case DEFAULT -> "default";
                        case ACCEPT_EDITS -> "acceptEdits";
                        case BYPASS_PERMISSIONS -> "bypassPermissions";
                    };
                    ctx.getUi().addSystemMessage(modeStr);
                }
        ));

        // 11. /session
        cmds.add(new Command(
                "session",
                List.of(),
                "显示当前会话详情",
                "/session",
                CommandType.LOCAL,
                "",
                false,
                ctx -> {
                    String sid = ctx.getDialogue().getSessionId();
                    Path path = Paths.get("").toAbsolutePath()
                            .resolve(".bingtangcode").resolve("sessions").resolve(sid + ".jsonl");
                    ctx.getUi().addSystemMessage("会话ID: " + sid);
                    ctx.getUi().addSystemMessage("存档路径: " + path.toAbsolutePath().toString());
                }
        ));

        // 12. /review
        cmds.add(new Command(
                "review",
                List.of(),
                "代码审查",
                "/review",
                CommandType.PROMPT,
                "",
                false,
                ctx -> ctx.getUi().sendUserMessage("请审查上下文中的代码变更，指出潜在 bug、可读性问题、可简化处。")
        ));

        return cmds;
    }

    private static List<String> getLoadedMemoryFiles(Path projectRoot) {
        List<String> files = new ArrayList<>();
        Path projDir = projectRoot.resolve(".bingtangcode").resolve("memory");
        if (Files.exists(projDir) && Files.isDirectory(projDir)) {
            try (Stream<Path> stream = Files.list(projDir)) {
                stream.filter(Files::isRegularFile)
                      .map(p -> p.getFileName().toString())
                      .filter(name -> name.endsWith(".md") && !name.equals("MEMORY.md"))
                      .forEach(files::add);
            } catch (Exception ignored) {}
        }
        Path userDir = Paths.get(System.getProperty("user.home"))
                                      .resolve(".bingtangcode").resolve("memory");
        if (Files.exists(userDir) && Files.isDirectory(userDir)) {
            try (Stream<Path> stream = Files.list(userDir)) {
                stream.filter(Files::isRegularFile)
                      .map(p -> p.getFileName().toString())
                      .filter(name -> name.endsWith(".md") && !name.equals("MEMORY.md"))
                      .forEach(files::add);
            } catch (Exception ignored) {}
        }
        return files;
    }

    private static int visualWidth(String s) {
        if (s == null) return 0;
        int len = 0;
        boolean inAnsi = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inAnsi) {
                if (c == 'm') inAnsi = false;
                continue;
            }
            if (c == '\033') { inAnsi = true; continue; }
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3000 && c <= 0x303F
                    || c >= 0xFF00 && c <= 0xFFEF) {
                len += 2;
            } else {
                len++;
            }
        }
        return len;
    }

    private static String padRightVisual(String s, int targetWidth) {
        int w = visualWidth(s);
        if (w >= targetWidth) return s;
        return s + " ".repeat(targetWidth - w);
    }
}
