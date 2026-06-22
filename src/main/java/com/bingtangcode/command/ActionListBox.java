package com.bingtangcode.command;

import com.bingtangcode.tui.TerminalIO;
import java.util.List;

public class ActionListBox {
    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";
    private static final String REVERSE = "\033[7m";
    private static final String BOLD = "\033[1m";

    private final TerminalIO terminalIO;

    public ActionListBox(TerminalIO terminalIO) {
        this.terminalIO = terminalIO;
    }

    public int select(String title, List<String> items, int defaultIndex) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        int selected = defaultIndex;
        if (selected < 0 || selected >= items.size()) {
            selected = 0;
        }

        String hint = "↑↓ 键选择，Enter 键确认，q/ESC 取消";
        printBox(title, items, selected, hint);

        while (true) {
            String key = terminalIO.readKey();
            switch (key) {
                case "UP", "k" -> {
                    if (selected > 0) {
                        clearBox(items.size());
                        selected--;
                        printBox(title, items, selected, hint);
                    }
                }
                case "DOWN", "j" -> {
                    if (selected < items.size() - 1) {
                        clearBox(items.size());
                        selected++;
                        printBox(title, items, selected, hint);
                    }
                }
                case "ENTER" -> {
                    clearBox(items.size());
                    return selected;
                }
                case "q", "Q", "ESCAPE", "NONE" -> {
                    clearBox(items.size());
                    return -1;
                }
            }
        }
    }

    private void printBox(String title, List<String> items, int selected, String hint) {
        int termW = terminalIO.getWidth();
        int maxW = visualWidth(title) + 4;
        for (String item : items) {
            int w = visualWidth("  " + item) + 4;
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
        for (int i = 0; i < items.size(); i++) {
            String line = "  " + items.get(i);
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

    private void clearBox(int itemsCount) {
        int linesCount = itemsCount + 7 + 1; // 1 for leading newline
        System.out.print("\033[" + linesCount + "A\033[J");
        System.out.flush();
    }

    private static String center(String s, int width) {
        int vw = visualWidth(s);
        int before = (width - vw) / 2;
        int after = width - vw - before;
        return " ".repeat(Math.max(0, before)) + s + " ".repeat(Math.max(0, after));
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
}
