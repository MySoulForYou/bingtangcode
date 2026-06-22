package com.bingtangcode.tui;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.bingtangcode.command.UIController;
import com.bingtangcode.agent.AgentLoop;
import com.bingtangcode.permission.PermissionGate;
import com.bingtangcode.permission.PermissionMode;

public class TerminalIO implements UIController {

    private AgentLoop agentLoop;
    private PermissionGate permissionGate;
    private com.bingtangcode.command.CommandRegistry commandRegistry;

    public void setAgentLoop(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    public void setPermissionGate(PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    public void setCommandRegistry(com.bingtangcode.command.CommandRegistry registry) {
        this.commandRegistry = registry;
    }

    private static final String RESET = "\033[0m";
    private static final String GRAY = "\033[90m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String BOLD = "\033[1m";

    private static final String CLAUDE_CORAL = "\033[38;5;173m";
    private static final String YELLOW_BOLD = "\033[1;33m";

    private static final Path HISTORY_PATH = Paths.get(
            System.getProperty("user.home"), ".bingtangcode", "history");

    private static final String HLINE = "─";
    private static final String TL = "╭";
    private static final String TR = "╮";
    private static final String BL = "╰";
    private static final String BR = "╯";
    private static final String VLINE = "│";

    private final Terminal terminal;
    private final LineReader reader;

    private int totalTokens = 0;
    private String modelName = "";
    private String appVersion = "";

    private boolean inWelcome = false;
    private String welcomeProvider = null;
    private String welcomeWorkDir = null;

    private Runnable shiftTabHandler;
    private String promptModeLabel = "";

    private final ExecutorService confirmExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "confirm-reader");
        t.setDaemon(true);
        return t;
    });

    public TerminalIO() {
        try {
            this.terminal = TerminalBuilder.builder()
                    .system(true)
                    .streams(System.in, System.out)
                    .encoding("UTF-8")
                    .build();
            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, HISTORY_PATH)
                    .completer((reader1, line, candidates) -> {
                        if (commandRegistry == null) return;
                        String word = line.word();
                        if (word != null && word.startsWith("/")) {
                            List<String> matches = commandRegistry.complete(word);
                            for (String match : matches) {
                                com.bingtangcode.command.Command cmd = commandRegistry.find(match.substring(1));
                                String desc = (cmd != null) ? cmd.getDescription() : "";
                                candidates.add(new org.jline.reader.Candidate(
                                    match, match, null, desc, null, null, true
                                ));
                            }
                        }
                    })
                    .build();
            this.reader.setOpt(LineReader.Option.AUTO_MENU);
            this.reader.setOpt(LineReader.Option.AUTO_LIST);
        } catch (IOException e) {
            throw new RuntimeException("无法初始化终端: " + e.getMessage(), e);
        }
    }

    public int getWidth() {
        Integer w = terminal.getWidth();
        return (w != null && w > 0) ? w : 80;
    }

    private String getGitBranch() {
        try {
            Path gitHead = Paths.get(".git", "HEAD");
            if (Files.exists(gitHead)) {
                String content = Files.readString(gitHead).trim();
                if (content.startsWith("ref: refs/heads/")) {
                    return content.substring("ref: refs/heads/".length());
                } else if (content.length() >= 7) {
                    return content.substring(0, 7);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void setModelName(String name) {
        this.modelName = name;
    }

    public void setVersion(String version) {
        this.appVersion = version;
    }

    /** 设置输入框内 prompt 左侧的模式标签（如 "Plan"），空字符串表示不显示。 */
    public void setPromptModeLabel(String label) {
        this.promptModeLabel = label;
    }

    public void setTotalTokens(int tokens) {
        this.totalTokens = tokens;
    }

    private void drawInputTop() {
        int w = getWidth() - 2;
        System.out.println(GRAY + TL + HLINE.repeat(Math.max(0, w)) + TR + RESET);
    }

    /** 独立绘制状态行（简洁版，启动/模式切换后使用）。 */
    public void drawStatusBar() {
        String branch = getGitBranch();
        StringBuilder sb = new StringBuilder();
        if (branch != null) {
            sb.append("  ").append(branch);
        }
        sb.append("  ").append(modelName);
        sb.append("  ").append(totalTokens).append(" tokens");
        System.out.println(GRAY + sb + RESET);
    }

    private void drawInputBottom() {
        String branch = getGitBranch();

        StringBuilder left = new StringBuilder();
        if (branch != null) {
            left.append(" ").append(branch).append(" ");
        }

        String tokenStr;
        if (totalTokens >= 1000) {
            tokenStr = String.format("%.1fk tokens", totalTokens / 1000.0);
        } else {
            tokenStr = totalTokens + " tokens";
        }
        String rightPart = modelName + " (" + tokenStr + ") ";

        int leftVisual = BuddyManager.getVisualWidth(left.toString());
        int rightVisual = BuddyManager.getVisualWidth(rightPart);
        int w = getWidth() - 2;
        int gap = Math.max(0, w - leftVisual - rightVisual - 2);

        System.out.println(GRAY + BL + HLINE + left + RESET
                + GRAY + HLINE.repeat(gap) + rightPart + HLINE + BR + RESET);
    }

    private boolean handleLocalCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String baseCmd = parts[0];

        if ("/buddy".equals(baseCmd)) {
            System.out.println(BuddyManager.getBuddySprite());
            return true;
        }

        if ("/clear".equals(baseCmd)) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return true;
        }

        if ("/help".equals(baseCmd)) {
            printHelpCard();
            return true;
        }

        return false;
    }

    private void printHelpCard() {
        String[] lines = {
                "  /exit, /quit      退出程序",
                "  /clear            清除屏幕",
                "  /help             显示此帮助",
                "  /plan             进入 Plan 模式（仅只读工具）",
                "  /do               回到 Default 模式",
                "  /mode             打开权限模式选择菜单",
                "  Shift+Tab         循环切换权限模式"
        };
        int boxW = 48;
        System.out.println();
        System.out.println(GRAY + "  ╭" + "─".repeat(boxW) + "╮" + RESET);
        System.out.println(GRAY + "  │" + RESET + "  " + BOLD + center("命令帮助", boxW) + GRAY + "  │" + RESET);
        System.out.println(GRAY + "  │" + RESET + "  " + " ".repeat(boxW) + GRAY + "  │" + RESET);
        for (String line : lines) {
            System.out.println(GRAY + "  │" + RESET + "  " + line + " ".repeat(Math.max(0, boxW - visualWidth(line))) + GRAY + "  │" + RESET);
            System.out.flush();
        }
        System.out.println(GRAY + "  │" + RESET + "  " + " ".repeat(boxW) + GRAY + "  │" + RESET);
        System.out.println(GRAY + "  │" + RESET + "  " + GRAY + "按任意键关闭" + " ".repeat(Math.max(0, boxW - visualWidth("按任意键关闭"))) + GRAY + "  │" + RESET);
        System.out.println(GRAY + "  ╰" + "─".repeat(boxW) + "╯" + RESET);
        System.out.println();
    }

    private static String center(String s, int width) {
        int vw = visualWidth(s);
        int before = (width - vw) / 2;
        return " ".repeat(Math.max(0, before)) + s;
    }

    private static int visualWidth(String s) {
        int len = 0;
        boolean inAnsi = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inAnsi) { if (c == 'm') inAnsi = false; continue; }
            if (c == '\033') { inAnsi = true; continue; }
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3000 && c <= 0x303F || c >= 0xFF00 && c <= 0xFFEF) len += 2;
            else len++;
        }
        return len;
    }

    /**
     * 命令确认对话框。高亮显示命令文本，用户输入 y/N，10 秒无输入自动拒绝。
     * @return true=确认执行，false=拒绝
     */
    public boolean confirmCommand(String command) {
        System.out.println();
        System.out.println(GRAY + TL + HLINE.repeat(Math.min(command.length() + 20, getWidth() - 2)) + TR + RESET);
        System.out.println(GRAY + VLINE + " " + YELLOW_BOLD + "将执行命令:" + RESET);
        System.out.println(GRAY + VLINE + " " + RESET + command);
        System.out.println(GRAY + BL + HLINE.repeat(Math.min(command.length() + 20, getWidth() - 2)) + BR + RESET);
        System.out.print(GRAY + "  [y/N] " + RESET);
        System.out.flush();

        Future<String> future = confirmExecutor.submit(() -> reader.readLine());
        try {
            String input = future.get(10, TimeUnit.SECONDS);
            boolean confirmed = input != null && input.trim().equalsIgnoreCase("y");
            if (!confirmed) {
                System.out.println(GRAY + "  已取消" + RESET);
            }
            return confirmed;
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            System.out.println(GRAY + "  超时，已自动取消" + RESET);
            return false;
        } catch (Exception e) {
            System.out.println(GRAY + "  已取消" + RESET);
            return false;
        }
    }

    public String readLine(String prompt) throws UserInterruptException {
        System.out.println();

        while (true) {
            int w = getWidth() - 2;

            // 输入框：上边 + 空行 + 下边
            drawInputTop();
            System.out.println(GRAY + VLINE + " " + RESET + " ".repeat(Math.max(0, w - 1)) + GRAY + VLINE + RESET);
            System.out.print(GRAY + BL + HLINE.repeat(Math.max(0, w)) + BR + RESET);
            System.out.print("\033[1A\r");
            System.out.flush();

            String modeLabel = !promptModeLabel.isEmpty()
                    ? promptModeLabel + " " : "";
            String firstPrompt = GRAY + VLINE + " " + modeLabel + CLAUDE_CORAL + "❯ " + RESET;
            String line = reader.readLine(firstPrompt);

            if (line == null) {
                System.out.print("\033[2A\033[J");
                return null;
            }

            String trimmedFirst = line.trim();

            if (trimmedFirst.isEmpty()) {
                System.out.print("\033[3A\033[J");
                continue;
            }

            System.out.print("\033[J");
            drawInputBottom();
            // / 开头的本地命令不发 API，不估 token
            if (!trimmedFirst.startsWith("/")) {
                int inputTokens = 200 + (line.length() / 4);
                totalTokens += inputTokens;
            }
            return line;
        }
    }

    public void printTopBorder() {
        inWelcome = true;
    }

    public void printBorderLine(String content) {
        if (inWelcome) {
            try {
                if (content.contains("·") && welcomeProvider == null) {
                    int dotIdx = content.indexOf("·");
                    int parenIdx = content.indexOf(")");
                    if (parenIdx != -1 && parenIdx < dotIdx) {
                        welcomeProvider = content.substring(parenIdx + 1, dotIdx).trim();
                        welcomeWorkDir = content.substring(dotIdx + 1).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void printBottomBorder() {
        if (inWelcome) {
            inWelcome = false;
            printCustomWelcomeBanner();
        } else {
            printSeparator();
        }
    }

    private void printCustomWelcomeBanner() {
        String provider = (welcomeProvider != null) ? welcomeProvider : "OpenAI";
        String workDir = (welcomeWorkDir != null) ? welcomeWorkDir : "~/Documents/QAQcode";

        try {
            com.bingtangcode.config.ConfigManager config = new com.bingtangcode.config.ConfigManager();
            String p = config.getProvider();
            String model = "";
            if ("anthropic".equals(p)) {
                model = config.getAnthropicModel();
            } else if ("openai".equals(p)) {
                model = config.getOpenAiModel();
            }
            if (model != null && !model.isEmpty()) {
                provider = p + " (" + model + ")";
            }
        } catch (Exception ignored) {
        }

        String[] buddyLines = com.bingtangcode.tui.BuddyManager.getBuddySprite().split("\n");
        int buddyWidth = 0;
        for (String l : buddyLines) {
            buddyWidth = Math.max(buddyWidth, BuddyManager.getVisualWidth(BuddyManager.stripAnsi(l)));
        }

        String[] infoLines = new String[3];
        infoLines[0] = BOLD + "bingtangCode v" + appVersion + RESET;
        infoLines[1] = GRAY + provider + " · " + workDir + RESET;
        infoLines[2] = GRAY + "Type /help for options" + RESET;

        int infoWidth = 0;
        for (String l : infoLines) {
            infoWidth = Math.max(infoWidth, BuddyManager.getVisualWidth(BuddyManager.stripAnsi(l)));
        }

        int contentWidth = Math.max(buddyWidth, infoWidth);

        System.out.println();
        System.out.println(CYAN + TL + HLINE.repeat(contentWidth + 2) + TR + RESET);

        for (String line : buddyLines) {
            String padded = BuddyManager.padColorizedLine(line, contentWidth);
            System.out.println(CYAN + VLINE + " " + RESET + padded + CYAN + " " + VLINE + RESET);
        }

        System.out.println(CYAN + VLINE + " " + RESET + " ".repeat(contentWidth) + CYAN + " " + VLINE + RESET);

        for (String line : infoLines) {
            String padded = BuddyManager.padColorizedLine(line, contentWidth);
            System.out.println(CYAN + VLINE + " " + RESET + padded + CYAN + " " + VLINE + RESET);
        }

        System.out.println(CYAN + BL + HLINE.repeat(contentWidth + 2) + BR + RESET);
        System.out.println();
    }

    public void printSeparator() {
        int w = getWidth() - 2;
        System.out.println(GRAY + HLINE.repeat(Math.max(0, Math.min(w, 80))) + RESET);
    }

    public void printSystem(String text) {
        System.out.println(GRAY + "> " + text + RESET);
    }

    public void printAssistantPrefix() {
        System.out.flush();
    }

    public void printToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        totalTokens += Math.max(1, token.length() / 4);
        System.out.print(token);
        System.out.flush();
    }

    /** 只累计 token 计数，不打印。用于 thinking/reasoning 等已有独立渲染的流 */
    public void countToken(String token) {
        if (token != null && !token.isEmpty()) {
            totalTokens += Math.max(1, token.length() / 4);
        }
    }

    public void printError(String text) {
        System.out.println(RED + "⨯ " + text + RESET);
    }

    public void newline() {
        System.out.println();
    }

    /** 读取一行原始输入，不画输入框。用于 /mode 菜单选择等场景。 */
    public String readRawLine() {
        try {
            String line = reader.readLine("");
            return line != null ? line : "";
        } catch (Exception e) {
            return "";
        }
    }

    public void setShiftTabHandler(Runnable action) {
        this.shiftTabHandler = action;
        if (action != null) {
            reader.getKeyMaps().get(LineReader.MAIN).bind(new Widget() {
                @Override
                public boolean apply() {
                    Runnable h = shiftTabHandler;
                    if (h != null) {
                        h.run();
                    }
                    return true;
                }
            }, "\033[Z");
        }
    }

    public void setInterruptHandler(Runnable action) {
        terminal.handle(Signal.INT, sig -> action.run());
    }

    public void printInterrupted() {
        System.out.println(GRAY + "  ^C 终止" + RESET);
    }

    /**
     * 读取单个按键。用于人在回路等需要非行编辑输入的场景。
     * 先用 JLine3 的 enterRawMode 强制无回显模式，读键后不恢复（下次 readLine 会自行设置）。
     * 返回的字符串：普通字符为单字符 "y"/"1"，方向键为 "UP"/"DOWN"，Enter 为 "ENTER"。
     */
    public String readKey() {
        try {
            terminal.enterRawMode();
            var nr = terminal.reader();

            int ch = nr.read();
            if (ch == -1) return "NONE";
            if (ch == '\r' || ch == '\n') return "ENTER";
            if (ch == '\t') return "TAB";
            if (ch == 27) {
                int ch2 = nr.read(200);
                if (ch2 == -1) return "NONE";
                if (ch2 == '[') {
                    int ch3 = nr.read(200);
                    if (ch3 == -1) return "NONE";
                    return switch (ch3) {
                        case 'A' -> "UP"; case 'B' -> "DOWN";
                        case 'C' -> "RIGHT"; case 'D' -> "LEFT";
                        default -> "NONE";
                    };
                }
                if (ch2 == 'O') {
                    int ch3 = nr.read(200);
                    if (ch3 == -1) return "NONE";
                    return switch (ch3) {
                        case 'A' -> "UP"; case 'B' -> "DOWN";
                        default -> "NONE";
                    };
                }
                return "NONE";
            }
            return String.valueOf((char) ch);
        } catch (Exception e) {
            return "NONE";
        }
    }

    public void syncModeLabel() {
        if (agentLoop == null) return;
        PermissionMode mode = agentLoop.getMode();
        String label = modeLabel(mode);
        String color = switch (mode) {
            case PLAN ->                     "\033[33m";
            case BYPASS_PERMISSIONS ->       "\033[31m";
            case ACCEPT_EDITS ->             "\033[36m";
            default ->                       "\033[90m";
        };
        this.setPromptModeLabel(color + label + RESET);
    }

    public void printModeSwitch(String desc) {
        System.out.println();
        System.out.println(GRAY + "  ~ " + desc + RESET);
        syncModeLabel();
        this.drawStatusBar();
    }

    private static String modeLabel(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> "Default";
            case ACCEPT_EDITS -> "AcceptEdits";
            case PLAN -> "Plan";
            case BYPASS_PERMISSIONS -> "Bypass";
        };
    }

    @Override
    public void addSystemMessage(String text) {
        System.out.println(GRAY + "  " + text + RESET);
    }

    @Override
    public void sendUserMessage(String text) {
        System.out.println(CLAUDE_CORAL + "❯ " + RESET + text);
        if (agentLoop != null) {
            agentLoop.run(text);
        }
    }

    @Override
    public void setPlanMode(boolean enabled) {
        PermissionMode newMode = enabled ? PermissionMode.PLAN : PermissionMode.DEFAULT;
        if (agentLoop != null) {
            agentLoop.setMode(newMode);
        }
        if (permissionGate != null) {
            permissionGate.setMode(newMode);
        }
        syncModeLabel();
        printModeSwitch(modeLabel(newMode) + " Mode");
    }

    @Override
    public int getSessionInputTokens() {
        return agentLoop != null ? agentLoop.getSessionInputTokens() : 0;
    }

    @Override
    public int getSessionOutputTokens() {
        return agentLoop != null ? agentLoop.getSessionOutputTokens() : 0;
    }

    @Override
    public int getSessionRoundCount() {
        return agentLoop != null ? agentLoop.getSessionRoundCount() : 0;
    }

    @Override
    public void refreshStatus() {
        syncModeLabel();
        drawStatusBar();
    }

    @Override
    public void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    @Override
    public int selectFromList(String title, List<String> items, int defaultIndex) {
        com.bingtangcode.command.ActionListBox listBox = new com.bingtangcode.command.ActionListBox(this);
        return listBox.select(title, items, defaultIndex);
    }

    public void shutdown() {
        confirmExecutor.shutdownNow();
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
    }
}
