package com.bingtangcode.core;

import com.bingtangcode.agent.AgentLoop;
import com.bingtangcode.permission.PermissionGate;
import com.bingtangcode.permission.PermissionMode;
import com.bingtangcode.tui.TerminalIO;

import java.nio.file.Paths;


public class SessionManager {

    private static final String GRAY = "\033[90m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";
    private static final String REVERSE = "\033[7m";
    private static final String BOLD = "\033[1m";

    private final TerminalIO terminalIO;
    private final AgentLoop agentLoop;
    private final PermissionGate permissionGate;
    private final Runnable cancelAction;
    private final com.bingtangcode.command.CommandRegistry registry;
    private final com.bingtangcode.config.ConfigManager config;

    public SessionManager(TerminalIO terminalIO, AgentLoop agentLoop,
                          PermissionGate permissionGate, Runnable cancelAction,
                          com.bingtangcode.command.CommandRegistry registry,
                          com.bingtangcode.config.ConfigManager config) {
        this.terminalIO = terminalIO;
        this.agentLoop = agentLoop;
        this.permissionGate = permissionGate;
        this.cancelAction = cancelAction;
        this.registry = registry;
        this.config = config;
    }

    public void start() {
        printWelcome();

        terminalIO.setShiftTabHandler(this::cycleModeSilent);
        syncModeLabel();
        terminalIO.drawStatusBar();

        while (true) {
            String input;
            try {
                input = terminalIO.readLine("> ");
                if (input == null) {
                    break;
                }
            } catch (Exception e) {
                continue;
            }

            input = input.trim();
            if (input.isEmpty()) {
                continue;
            }

            // 命令拦截与分流
            if (input.startsWith("/")) {
                String name;
                String args;
                String commandLine = input.substring(1).trim();
                int firstSpace = commandLine.indexOf(' ');
                if (firstSpace == -1) {
                    name = commandLine.toLowerCase();
                    args = "";
                } else {
                    name = commandLine.substring(0, firstSpace).toLowerCase();
                    args = commandLine.substring(firstSpace + 1).trim();
                }

                // 若仅输入了 "/"
                if (name.isEmpty()) {
                    com.bingtangcode.command.Command helpCmd = registry.find("help");
                    if (helpCmd != null) {
                        try {
                            com.bingtangcode.command.CommandContext ctx = new com.bingtangcode.command.CommandContext(
                                    "", terminalIO, agentLoop.getDialogue(), agentLoop, config, registry
                            );
                            helpCmd.getHandler().handle(ctx);
                        } catch (Exception e) {
                            terminalIO.addSystemMessage("显示帮助卡片失败: " + e.getMessage());
                        }
                    }
                    continue;
                }

                com.bingtangcode.command.Command cmd = registry.find(name);
                if (cmd == null) {
                    terminalIO.addSystemMessage("未知命令: " + name + ", 输入 /help 查看可用命令");
                    continue;
                }

                if (args.isEmpty() && !cmd.getArgPrompt().isEmpty()) {
                    terminalIO.addSystemMessage(cmd.getArgPrompt());
                    continue;
                }

                try {
                    com.bingtangcode.command.CommandContext ctx = new com.bingtangcode.command.CommandContext(
                            args, terminalIO, agentLoop.getDialogue(), agentLoop, config, registry
                    );
                    cmd.getHandler().handle(ctx);
                } catch (Exception e) {
                    terminalIO.addSystemMessage("命令执行异常: " + e.getMessage());
                }
                continue;
            }

            terminalIO.printAssistantPrefix();

            terminalIO.setInterruptHandler(() -> {
                agentLoop.cancel();
                cancelAction.run();
            });

            agentLoop.run(input);

            terminalIO.setInterruptHandler(null);
        }

        terminalIO.shutdown();
    }

    // ==================== 模式切换 ====================

    private void cycleMode() {
        PermissionMode current = agentLoop.getMode();
        PermissionMode next = switch (current) {
            case DEFAULT -> PermissionMode.ACCEPT_EDITS;
            case ACCEPT_EDITS -> PermissionMode.PLAN;
            case PLAN -> PermissionMode.BYPASS_PERMISSIONS;
            case BYPASS_PERMISSIONS -> PermissionMode.DEFAULT;
        };
        applyMode(next);
        printModeSwitch(modeLabel(next) + " Mode");
    }

    /** Shift+Tab 版本的静默切换——不打印任何东西，避免破坏正在输入的输入框。 */
    private void cycleModeSilent() {
        PermissionMode current = agentLoop.getMode();
        PermissionMode next = switch (current) {
            case DEFAULT -> PermissionMode.ACCEPT_EDITS;
            case ACCEPT_EDITS -> PermissionMode.PLAN;
            case PLAN -> PermissionMode.BYPASS_PERMISSIONS;
            case BYPASS_PERMISSIONS -> PermissionMode.DEFAULT;
        };
        applyMode(next);
        syncModeLabel();
    }

    private void showModeMenu() {
        PermissionMode[] modes = {PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN, PermissionMode.BYPASS_PERMISSIONS};
        String[] labels = {"Default          只读 Allow，写/执行 Ask",
                "Accept Edits     只读/写 Allow，执行 Ask",
                "Plan             仅可用只读工具",
                "Bypass           全部 Allow（黑名单/沙箱仍拦截）"};

        int selected = java.util.Arrays.asList(modes).indexOf(agentLoop.getMode());
        if (selected < 0) selected = 0;

        printMenuBox(labels, selected, "选择权限模式",
                "↑↓ 选择  Enter 确认  数字键直选");

        while (true) {
            String key = terminalIO.readKey();
            switch (key) {
                case "UP", "k" -> {
                    if (selected > 0) { clearMenuBox(); selected--; printMenuBox(labels, selected,
                            "选择权限模式", "↑↓ 选择  Enter 确认  数字键直选"); }
                }
                case "DOWN", "j" -> {
                    if (selected < labels.length - 1) { clearMenuBox(); selected++;
                            printMenuBox(labels, selected, "选择权限模式",
                                    "↑↓ 选择  Enter 确认  数字键直选"); }
                }
                case "ENTER" -> {
                    clearMenuBox();
                    applyMode(modes[selected]);
                    printModeSwitch(modeLabel(modes[selected]) + " Mode");
                    return;
                }
                case "1" -> { clearMenuBox(); applyMode(modes[0]); printModeSwitch("Default Mode"); return; }
                case "2" -> { clearMenuBox(); applyMode(modes[1]); printModeSwitch("Accept Edits Mode"); return; }
                case "3" -> { clearMenuBox(); applyMode(modes[2]); printModeSwitch("Plan Mode"); return; }
                case "4" -> { clearMenuBox(); applyMode(modes[3]); printModeSwitch("Bypass Mode"); return; }
            }
        }
    }

    /** 绘制通用选择菜单 */
    private void printMenuBox(String[] labels, int selected, String title, String hint) {
        int termW = terminalIO.getWidth();
        int maxW = visualWidth(title) + 4;
        for (String l : labels) {
            int w = visualWidth("  " + l) + 4;
            if (w > maxW) maxW = w;
        }
        if (visualWidth(hint) + 4 > maxW) maxW = visualWidth(hint) + 4;
        int boxW = Math.min(Math.max(maxW, 40), termW - 4);

        String top = GRAY + "  ╭" + "─".repeat(boxW) + "╮" + RESET;
        String mid = GRAY + "  │" + RESET;
        String bot = GRAY + "  ╰" + "─".repeat(boxW) + "╯" + RESET;

        System.out.println();
        System.out.println(top);
        System.out.println(mid + "  " + center(title, boxW) + mid);
        System.out.println(mid + "  " + " ".repeat(boxW) + mid);
        for (int i = 0; i < labels.length; i++) {
            String line = "  " + labels[i];
            if (i == selected) {
                System.out.println(mid + "  " + REVERSE + BOLD + padRight(line, boxW, ' ') + RESET + mid);
            } else {
                System.out.println(mid + "  " + GRAY + padRight(line, boxW, ' ') + RESET + mid);
            }
        }
        System.out.println(mid + "  " + " ".repeat(boxW) + mid);
        System.out.println(mid + "  " + GRAY + padRight(hint, boxW, ' ') + RESET + mid);
        System.out.println(bot);
        System.out.flush();
    }

    private void clearMenuBox() {
        // 空行 + 顶边 + 标题 + 空行 + 4选项 + 空行 + 提示 + 底边 = 10 行
        System.out.print("\033[11A\033[J");
        System.out.flush();
    }

    private void applyMode(PermissionMode mode) {
        agentLoop.setMode(mode);
        if (permissionGate != null) {
            permissionGate.setMode(mode);
        }
    }

    private void printModeSwitch(String desc) {
        System.out.println();
        System.out.println(GRAY + "  ~ " + desc + RESET);
        syncModeLabel();
        terminalIO.drawStatusBar();
    }

    // ==================== UI 同步 ====================

    private void syncModeLabel() {
        PermissionMode mode = agentLoop.getMode();
        String label = modeLabel(mode);
        String color = switch (mode) {
            case PLAN ->                     "\033[33m";
            case BYPASS_PERMISSIONS ->       "\033[31m";
            case ACCEPT_EDITS ->             "\033[36m";
            default ->                       "\033[90m";
        };
        terminalIO.setPromptModeLabel(color + label + RESET);
    }

    // ==================== 菜单 UI ====================

    private static String center(String s, int width) {
        int vw = visualWidth(s);
        int before = (width - vw) / 2;
        int after = width - vw - before;
        return " ".repeat(Math.max(0, before)) + s + " ".repeat(Math.max(0, after));
    }

    // ==================== 工具方法 ====================

    private static String modeLabel(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> "Default";
            case ACCEPT_EDITS -> "AcceptEdits";
            case PLAN -> "Plan";
            case BYPASS_PERMISSIONS -> "Bypass";
        };
    }

    private static int visualWidth(String s) {
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

    private static String padRight(String s, int width, char pad) {
        int vw = visualWidth(s);
        if (vw >= width) return s;
        return s + String.valueOf(pad).repeat(width - vw);
    }

    private void printWelcome() {
        String workDir = shortenPath(Paths.get("").toAbsolutePath().toString());

        terminalIO.printTopBorder();
        terminalIO.printBorderLine("  /) /)  bingtangCode");
        terminalIO.printBorderLine(" ( T_T)  " + workDir);
        terminalIO.printBorderLine(" c(\")(\") /help 查看帮助  ·  /exit 退出");
        terminalIO.printBottomBorder();
        System.out.println();
    }

    private static String shortenPath(String path) {
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }
}
