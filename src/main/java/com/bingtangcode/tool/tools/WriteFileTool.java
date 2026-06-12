package com.bingtangcode.tool.tools;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteFileTool implements Tool {

    private final Path projectRoot;

    public WriteFileTool(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return """
创建或覆盖写入文件。\
如果文件不存在，会自动创建父目录后再写入；如果文件已存在，会直接用新内容覆盖旧内容。\
filePath 相对于项目根目录，会被规范化——写入会拒绝 ../ 路径越界的操作。\
注意：对于已有文件的修改，优先使用 edit_file 而非 write_file，以减少不必要的内容传输。""";

    }

    @Override
    public String getParametersSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "filePath": {"type": "string", "description": "文件路径，相对于项目根目录"},
            "content": {"type": "string", "description": "要写入的文件内容"}
          },
          "required": ["filePath", "content"]
        }
        """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePath = (String) params.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return new ToolResult(null, "参数错误: filePath 不能为空", true);
        }
        String content = (String) params.get("content");
        if (content == null) {
            return new ToolResult(null, "参数错误: content 不能为空", true);
        }

        Path resolved = projectRoot.resolve(filePath).normalize();
        if (!resolved.startsWith(projectRoot)) {
            return new ToolResult(null, "安全限制: 禁止访问项目目录以外的文件", true);
        }
        if (Files.isDirectory(resolved)) {
            return new ToolResult(null, "目标路径是一个目录: " + filePath, true);
        }

        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            return new ToolResult(null, "已写入文件: " + filePath, false);
        } catch (IOException e) {
            return new ToolResult(null, "写入文件失败: " + e.getMessage(), true);
        }
    }
}
