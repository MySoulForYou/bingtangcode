package com.bingtangcode.permission;

import java.util.Map;

public final class ToolFriendlyName {

    private ToolFriendlyName() {}

    /** 内部工具名 → 规则友好名映射。 */
    public static String friendlyName(String internalName) {
        return switch (internalName) {
            case "read_file" -> "Read";
            case "write_file" -> "Write";
            case "edit_file" -> "Edit";
            case "execute_command" -> "Bash";
            case "find_files" -> "Glob";
            case "search_content" -> "Grep";
            default -> internalName;
        };
    }

    /** 从 ToolCall.parameters 中提取主参数值。 */
    public static String extractMainParam(String internalName, Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        String key = switch (internalName) {
            case "read_file", "write_file", "edit_file" -> "filePath";
            case "execute_command" -> "command";
            case "find_files", "search_content" -> "directory";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }
}
