package com.bingtangcode.permission;

import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tui.TerminalIO;

public class PermissionPrompt implements HumanInTheLoopHandler {

    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";
    private static final String REVERSE = "\033[7m";
    private static final String BOLD = "\033[1m";

    private final TerminalIO terminalIO;

    public PermissionPrompt(TerminalIO terminalIO) {
        this.terminalIO = terminalIO;
    }

    @Override
    public AskResult ask(ToolCall tc, PermissionMode mode) {
        String friendlyName = ToolFriendlyName.friendlyName(tc.name());
        String mainParam = ToolFriendlyName.extractMainParam(tc.name(), tc.parameters());
        String desc = friendlyName + (mainParam != null && !mainParam.isEmpty()
                ? " " + mainParam : "");

        String[] labels = {
                "允许本次",
                "永久允许",
                "拒绝本次"
        };
        String[] shortcuts = {"1", "2", "3"};
        String[] extraKeys = {"y", "", "n / d"};

        int selected = 0;

        printPrompt(desc, labels, shortcuts, extraKeys, selected);

        while (true) {
            String key = terminalIO.readKey();
            switch (key) {
                case "UP", "k" -> {
                    if (selected > 0) {
                        clearPrompt();
                        selected--;
                        printPrompt(desc, labels, shortcuts, extraKeys, selected);
                    }
                }
                case "DOWN", "j" -> {
                    if (selected < labels.length - 1) {
                        clearPrompt();
                        selected++;
                        printPrompt(desc, labels, shortcuts, extraKeys, selected);
                    }
                }
                case "ENTER" -> {
                    AskResult r = mapSelection(selected);
                    clearPrompt();
                    printFeedback(r);
                    return r;
                }
                case "1", "y" -> {
                    clearPrompt();
                    printFeedback(AskResult.ALLOW_ONCE);
                    return AskResult.ALLOW_ONCE;
                }
                case "2" -> {
                    clearPrompt();
                    printFeedback(AskResult.ALLOW_FOREVER);
                    return AskResult.ALLOW_FOREVER;
                }
                case "3", "n", "d" -> {
                    clearPrompt();
                    printFeedback(AskResult.DENY_ONCE);
                    return AskResult.DENY_ONCE;
                }
            }
        }
    }

    // ==================== UI ====================

    private void printPrompt(String desc, String[] labels, String[] shortcuts,
                             String[] extraKeys, int selected) {
        int termW = terminalIO.getWidth();
        int maxContent = 0;
        for (int i = 0; i < labels.length; i++) {
            String ek = extraKeys[i].isEmpty() ? "" : " / " + extraKeys[i];
            String line = "  " + (i + 1) + ". " + labels[i] + "     [" + shortcuts[i] + ek + "]";
            int w = visualWidth(line);
            if (w > maxContent) maxContent = w;
        }
        int descW = visualWidth(desc) + 4;
        if (descW > maxContent) maxContent = descW;
        int titleW = visualWidth("权限确认") + 4;
        if (titleW > maxContent) maxContent = titleW;
        int hintW = visualWidth("未匹配任何规则，是否允许？") + 4;
        if (hintW > maxContent) maxContent = hintW;

        int boxW = Math.min(Math.max(maxContent, 40), termW - 4);
        String top = GRAY + "  " + "╭" + "─".repeat(boxW) + "╮" + RESET;
        String mid = GRAY + "  " + "│" + RESET;
        String bot = GRAY + "  " + "╰" + "─".repeat(boxW) + "╯" + RESET;

        System.out.println();
        System.out.println(top);
        System.out.println(mid + "  " + center("权限确认", boxW) + mid);
        System.out.println(mid + "  " + padRight("", boxW, ' ') + mid);
        System.out.println(mid + "  " + padRight(desc, boxW, ' ') + mid);
        System.out.println(mid + "  " + GRAY + padRight("未匹配任何规则，是否允许？", boxW, ' ') + RESET + mid);
        System.out.println(mid + "  " + padRight("", boxW, ' ') + mid);

        for (int i = 0; i < labels.length; i++) {
            String num = (i + 1) + ". ";
            String sc = extraKeys[i].isEmpty() ? shortcuts[i]
                    : shortcuts[i] + " / " + extraKeys[i];
            String left = num + labels[i];
            String right = "[" + sc + "]";
            int leftW = visualWidth(left);
            int rightW = visualWidth(right);
            int pad = boxW - 2 - leftW - rightW;
            if (pad < 1) pad = 1;
            String line = left + " ".repeat(pad) + right;
            if (i == selected) {
                System.out.println(mid + "  " + REVERSE + BOLD
                        + padRight(line, boxW - 2, ' ') + RESET + mid);
            } else {
                System.out.println(mid + "  " + GRAY
                        + padRight(line, boxW - 2, ' ') + RESET + mid);
            }
        }

        System.out.println(mid + "  " + padRight("", boxW, ' ') + mid);
        System.out.println(mid + "  " + GRAY + padRight("↑↓ 选择  Enter 确认  数字键直选", boxW, ' ')
                + RESET + mid);
        System.out.println(bot);
        System.out.flush();
    }

    private void clearPrompt() {
        // 空行 + 顶边 + 标题 + 空行 + 描述 + 提示 + 空行 + 3选项 + 空行 + 帮助 + 底边 = 12 行
        System.out.print("\033[13A\033[J");
        System.out.flush();
    }

    private void printFeedback(AskResult r) {
        String msg = switch (r) {
            case ALLOW_ONCE -> "已放行（本次）";
            case ALLOW_FOREVER -> "已放行（永久）";
            case DENY_ONCE -> "已拒绝";
        };
        System.out.println(GRAY + "  " + msg + RESET);
    }

    // ==================== helpers ====================

    private AskResult mapSelection(int idx) {
        return switch (idx) {
            case 0 -> AskResult.ALLOW_ONCE;
            case 1 -> AskResult.ALLOW_FOREVER;
            default -> AskResult.DENY_ONCE;
        };
    }

    private static String center(String s, int width) {
        int vw = visualWidth(s);
        int before = (width - vw) / 2;
        int after = width - vw - before;
        return " ".repeat(Math.max(0, before)) + s + " ".repeat(Math.max(0, after));
    }

    private static String padRight(String s, int width, char pad) {
        int vw = visualWidth(s);
        if (vw >= width) return s;
        return s + String.valueOf(pad).repeat(width - vw);
    }

    /** 去掉 ANSI 转义码后的可视宽度。CJK 字符算 2。 */
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
            if (c >= 0x4E00 && c <= 0x9FFF
                    || c >= 0x3000 && c <= 0x303F
                    || c >= 0xFF00 && c <= 0xFFEF) {
                len += 2;
            } else {
                len++;
            }
        }
        return len;
    }
}
