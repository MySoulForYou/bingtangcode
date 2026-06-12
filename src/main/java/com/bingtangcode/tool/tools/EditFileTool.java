package com.bingtangcode.tool.tools;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditFileTool implements Tool {

    private final Path projectRoot;

    public EditFileTool(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return """
精确替换文件中的一段文本（oldString → newString）。\
先在文件全文中搜索 oldString 的出现次数：出现 0 次返回错误"未找到匹配文本"，出现多次返回错误"匹配到 N 处，请缩小范围"，只有恰好出现 1 次才执行替换。\
匹配对空白字符（空格、换行、缩进）敏感，不会做 trim 或格式化处理。适合做精准的小范围代码修改，不适合大段重构或多文件改动。\
注意：调用本工具前必须先使用 read_file 读取目标文件，否则调用将失败。""";

    }

    @Override
    public String getParametersSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "filePath": {"type": "string", "description": "文件路径，相对于项目根目录"},
            "oldString": {"type": "string", "description": "要替换的原文本"},
            "newString": {"type": "string", "description": "替换后的新文本"}
          },
          "required": ["filePath", "oldString", "newString"]
        }
        """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePath = (String) params.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return new ToolResult(null, "参数错误: filePath 不能为空", true);
        }
        String oldString = (String) params.get("oldString");
        if (oldString == null || oldString.isEmpty()) {
            return new ToolResult(null, "参数错误: oldString 不能为空", true);
        }
        String newString = (String) params.get("newString");
        if (newString == null) {
            newString = "";
        }

        Path resolved = projectRoot.resolve(filePath).normalize();
        if (!resolved.startsWith(projectRoot)) {
            return new ToolResult(null, "安全限制: 禁止访问项目目录以外的文件", true);
        }
        if (!Files.exists(resolved)) {
            return new ToolResult(null, "文件不存在: " + filePath, true);
        }

        try {
            String content = Files.readString(resolved);

            // 统计 oldString 出现次数（按原文字符串匹配，空白字符敏感）==
            int index = 0;
            int matchCount = 0;
            while ((index = content.indexOf(oldString, index)) != -1) {
                matchCount++;
                index++;
            }

            if (matchCount == 0) {
                return new ToolResult(null, "未找到匹配文本", true);
            }
            if (matchCount > 1) {
                return new ToolResult(null, "匹配到 " + matchCount + " 处，请缩小范围", true);
            }

            String newContent = content.replace(oldString, newString);
            Files.writeString(resolved, newContent);
            return new ToolResult(null, "已替换 " + filePath + " 中的匹配文本", false);
        } catch (IOException e) {
            return new ToolResult(null, "编辑文件失败: " + e.getMessage(), true);
        }
    }
}
