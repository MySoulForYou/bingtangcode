package com.bingtangcode.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EnvExpander {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /**
     * 展开字符串中的 ${VAR} 占位符。
     * 若环境变量未定义，替换为空字符串并向 System.err 输出告警。
     */
    public static String expand(String input) {
        if (input == null) {
            return null;
        }

        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = System.getenv(varName);
            if (value == null) {
                System.err.println("[警告] 环境变量 " + varName + " 未定义，展开为空字符串");
                value = "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }
}
