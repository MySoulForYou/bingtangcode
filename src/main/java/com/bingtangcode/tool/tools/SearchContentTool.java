package com.bingtangcode.tool.tools;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchContentTool implements Tool {

    private final Path projectRoot;

    public SearchContentTool(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String getName() {
        return "search_content";
    }

    @Override
    public String getDescription() {
        return """
在项目文件中搜索包含 query 关键词的行，返回每行匹配的"文件:行号: 内容"格式结果。\
搜索使用字符串包含匹配（非正则），区分大小写，会递归遍历所有子目录。\
可通过 directory 参数限定搜索子目录，通过 filePattern 参数（glob）限定文件类型——比如搜索所有 Java 文件中包含"Tool"的行。""";

    }

    @Override
    public String getParametersSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "搜索关键词"},
            "directory": {"type": "string", "description": "搜索目录，相对于项目根目录，默认根目录"},
            "filePattern": {"type": "string", "description": "glob 模式限定文件名，如 *.java"}
          },
          "required": ["query"]
        }
        """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String query = (String) params.get("query");
        if (query == null || query.isBlank()) {
            return new ToolResult(null, "参数错误: query 不能为空", true);
        }

        String dir = (String) params.get("directory");
        Path searchRoot = (dir != null && !dir.isBlank()) ? projectRoot.resolve(dir) : projectRoot;
        Path normalizedRoot = searchRoot.normalize();
        if (!normalizedRoot.startsWith(projectRoot)) {
            return new ToolResult(null, "安全限制: 禁止访问项目目录以外的文件", true);
        }

        String filePattern = (String) params.get("filePattern");
        PathMatcher matcher = (filePattern != null && !filePattern.isBlank())
                ? FileSystems.getDefault().getPathMatcher("glob:" + filePattern)
                : null;

        List<String> results = new ArrayList<>();
        try (var stream = Files.walk(normalizedRoot)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path file = it.next();
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (matcher != null) {
                    Path name = file.getFileName();
                    if (!matcher.matches(name)) {
                        continue;
                    }
                }

                List<String> lines;
                try {
                    lines = Files.readAllLines(file);
                } catch (IOException e) {
                    continue; // 跳过二进制等不可读文件
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(query)) {
                        String relativePath = projectRoot.relativize(file).toString();
                        results.add(relativePath + ":" + (i + 1) + ": " + lines.get(i));
                    }
                }
            }
        } catch (IOException e) {
            return new ToolResult(null, "搜索内容失败: " + e.getMessage(), true);
        }

        if (results.isEmpty()) {
            return new ToolResult(null, "未找到包含 \"" + query + "\" 的内容", false);
        }
        return new ToolResult(null, String.join("\n", results), false);
    }
}
