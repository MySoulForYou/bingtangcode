package com.bingtangcode.tui;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TerminalIO {

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

    private boolean inWelcome = false;
    private String welcomeProvider = null;
    private String welcomeWorkDir = null;

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
                    .build();
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

    private void drawInputTop() {
        int w = getWidth() - 2;
        System.out.println(GRAY + TL + HLINE.repeat(Math.max(0, w)) + TR + RESET);
    }

    private void drawInputBottom() {
        String branch = getGitBranch();
        String leftPart = branch != null ? " " + branch + " " : "";

        String tokenStr;
        if (totalTokens >= 1000) {
            tokenStr = String.format("%.1fk tokens", totalTokens / 1000.0);
        } else {
            tokenStr = totalTokens + " tokens";
        }
        String rightPart = modelName + " (" + tokenStr + ") ";

        int leftVisual = BuddyManager.getVisualWidth(leftPart);
        int rightVisual = BuddyManager.getVisualWidth(rightPart);
        int w = getWidth() - 2;
        int gap = Math.max(0, w - leftVisual - rightVisual - 2);

        System.out.println(GRAY + BL + HLINE + leftPart + RESET
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
        int w = 54;
        System.out.println();
        System.out.println(CYAN + TL + HLINE.repeat(w) + TR + RESET);
        System.out.println(CYAN + VLINE + " " + RESET + BOLD + BuddyManager.padLine(" BINGTANGCODE 命令帮助", w - 2) + CYAN + " " + VLINE + RESET);
        System.out.println(CYAN + "├" + HLINE.repeat(w) + "┤" + RESET);
        System.out.println(CYAN + VLINE + " " + RESET + BuddyManager.padLine("  /exit 或 /quit      退出程序", w - 2) + CYAN + " " + VLINE + RESET);
        System.out.println(CYAN + VLINE + " " + RESET + BuddyManager.padLine("  /clear             清除屏幕历史", w - 2) + CYAN + " " + VLINE + RESET);
        System.out.println(CYAN + VLINE + " " + RESET + BuddyManager.padLine("  /buddy             显示当前吉祥物", w - 2) + CYAN + " " + VLINE + RESET);
        System.out.println(CYAN + VLINE + " " + RESET + BuddyManager.padLine("  /help              显示此帮助信息", w - 2) + CYAN + " " + VLINE + RESET);
        System.out.println(CYAN + BL + HLINE.repeat(w) + BR + RESET);
        System.out.println();
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

            String firstPrompt = GRAY + VLINE + " " + CLAUDE_CORAL + "❯ " + RESET;
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

            if (trimmedFirst.startsWith("/") && handleLocalCommand(trimmedFirst)) {
                System.out.print("\033[J");
                drawInputBottom();
                continue;
            }

            System.out.print("\033[J");
            drawInputBottom();
            int inputTokens = 200 + (line.length() / 4);
            totalTokens += inputTokens;
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
        infoLines[0] = BOLD + "bingtangCode v0.1.0" + RESET;
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

    public void printError(String text) {
        System.out.println(RED + "⨯ " + text + RESET);
    }

    public void newline() {
        System.out.println();
    }

    public void setInterruptHandler(Runnable action) {
        terminal.handle(Signal.INT, sig -> action.run());
    }

    public void printInterrupted() {
        System.out.println(GRAY + "  ^C 终止" + RESET);
    }

    public void shutdown() {
        confirmExecutor.shutdownNow();
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
    }
}
