package com.bingtangcode.tui;

public class BuddyManager {

    public static final String RESET = "\033[0m";
    // 保持和边框 UI 一致的青色 (Cyan)
    public static final String BUDDY_COLOR = "\033[36m";

    public static String[] getBuddyLines() {
        String[] lines = getRawBuddyLines();
        for (int i = 0; i < lines.length; i++) {
            // 统一染上颜色
            lines[i] = BUDDY_COLOR + lines[i] + RESET;
        }
        return lines;
    }

    private static String[] getRawBuddyLines() {
        // 单行紧凑版，总宽度约 55 字符，高度仅 3 行
        // 巧妙利用了半角方块 (▀, ▄) 增加细节，保证在极小尺寸下的可读性
        return new String[]{
                "█▀▄ █ █▀▄█ ▄▀▀▄   ▀█▀ ▄▀▄ █▀▄█ ▄▀▀▄   █ █ █ █ █   █ █",
                "█▀▄ █ █ ▀█ █ ▀▄    █  █▀█ █ ▀█ █ ▀▄   █▀█ █ █ █   █ █",
                "▀▀  ▀ ▀  ▀  ▀▀     ▀  ▀ ▀ ▀  ▀  ▀▀    ▀ ▀ ▀▀▀ ▀▀▀ ▀▀▀"
        };
    }

    public static String getBuddySprite() {
        String[] lines = getBuddyLines();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // --- 辅助函数（保持不变，用于对齐和宽度计算） ---

    public static int getVisualWidth(String str) {
        if (str == null) return 0;
        int width = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // 中文字符宽度算作 2
            if (c >= 0x4e00 && c <= 0x9fff) {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }

    public static String padLine(String content, int targetWidth) {
        int currentWidth = getVisualWidth(content);
        int pad = targetWidth - currentWidth;
        if (pad <= 0) {
            return content;
        }
        return content + " ".repeat(pad);
    }

    public static String stripAnsi(String str) {
        if (str == null) return "";
        return str.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    public static String padColorizedLine(String str, int targetWidth) {
        int visibleWidth = getVisualWidth(stripAnsi(str));
        int pad = targetWidth - visibleWidth;
        if (pad <= 0) {
            return str;
        }
        return str + " ".repeat(pad);
    }
}