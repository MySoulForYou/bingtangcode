package com.bingtangcode.tool.tools;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReadFileTool implements Tool {

    private final Path projectRoot;

    public ReadFileTool(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return """
读取项目中的文件内容，返回带行号前缀的文本。\
用于查看代码、配置文件或任何文本文件的内容。\
支持通过 startLine 和 endLine 参数指定读取范围（1-based，闭区间），适合只看文件某一部分的场景。\
文件不存在或访问越界时会返回 isError 为 true 的失败结果。""";

    }

    @Override
    public String getParametersSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "filePath": {"type": "string", "description": "文件路径，相对于项目根目录"},
            "startLine": {"type": "integer", "description": "起始行号（1-based，含）"},
            "endLine": {"type": "integer", "description": "结束行号（1-based，含）"}
          },
          "required": ["filePath"]
        }
        """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePath = (String) params.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return new ToolResult(null, "参数错误: filePath 不能为空", true);
        }

        Path resolved = projectRoot.resolve(filePath).normalize();
        if (!resolved.startsWith(projectRoot)) {
            return new ToolResult(null, "安全限制: 禁止访问项目目录以外的文件", true);
        }
        if (!Files.exists(resolved)) {
            return new ToolResult(null, "文件不存在: " + filePath, true);
        }
        if (!Files.isRegularFile(resolved)) {
            return new ToolResult(null, "不是普通文件: " + filePath, true);
        }

        try {
            List<String> allLines = Files.readAllLines(resolved);
            int startLine = getIntParam(params, "startLine", 1);
            int endLine = getIntParam(params, "endLine", allLines.size());

            if (startLine < 1 || endLine < 1 || startLine > endLine) {
                return new ToolResult(null, "行号范围不合法: " + startLine + "-" + endLine, true);
            }
            if (startLine > allLines.size()) {
                return new ToolResult(null, "起始行 " + startLine + " 超出文件总行数 " + allLines.size(), true);
            }

            int from = Math.max(0, startLine - 1);
            int to = Math.min(allLines.size(), endLine);

            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, allLines.get(i)));
            }
            return new ToolResult(null, sb.toString(), false);
        } catch (IOException e) {
            return new ToolResult(null, "读取文件失败: " + e.getMessage(), true);
        }
    }

    private static int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
