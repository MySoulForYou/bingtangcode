package com.bingtangcode.tool.tools;

import com.bingtangcode.permission.PathSandbox;
import com.bingtangcode.permission.PathViolationException;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FindFilesTool implements Tool {

    private final Path projectRoot;
    private final int maxResults;

    public FindFilesTool(Path projectRoot) {
        this(projectRoot, 200);
    }

    public FindFilesTool(Path projectRoot, int maxResults) {
        this.projectRoot = projectRoot;
        this.maxResults = maxResults;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return "find_files";
    }

    @Override
    public String getDescription() {
        return """
按 glob 模式在项目中搜索文件，返回匹配的文件路径列表（相对于项目根目录）。\
支持标准 glob 语法，如 *.java（所有 Java 文件）、src/**/*Test*.java（src 子树中的测试文件）。\
结果上限 200 条，超出后截断并附加提示。路径搜索会递归遍历子目录，搜索范围可通过 directory 参数限定子目录。""";

    }

    @Override
    public String getParametersSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {"type": "string", "description": "glob 模式，如 *.java、**\\/*Test*.java"},
            "directory": {"type": "string", "description": "搜索目录，相对于项目根目录，默认根目录"}
          },
          "required": ["pattern"]
        }
        """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pattern = (String) params.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return new ToolResult(null, "参数错误: pattern 不能为空", true);
        }

        String dir = (String) params.get("directory");
        Path searchRoot = (dir != null && !dir.isBlank()) ? projectRoot.resolve(dir) : projectRoot;
        Path normalizedRoot = searchRoot.normalize();
        try {
            PathSandbox.validate(projectRoot, dir != null && !dir.isBlank() ? dir : ".");
        } catch (PathViolationException e) {
            return new ToolResult(null, e.getMessage(), true);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<String> results = new ArrayList<>();
        try (var stream = Files.walk(normalizedRoot)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path file = it.next();
                Path name = (file.getParent() != null) ? file : file.getFileName();
                if (matcher.matches(name.getFileName()) || matcher.matches(normalizedRoot.relativize(file))) {
                    results.add(normalizedRoot.relativize(file).toString());
                    if (results.size() >= maxResults) {
                        results.add("... (已截断，结果超过 " + maxResults + " 条)");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            return new ToolResult(null, "搜索文件失败: " + e.getMessage(), true);
        }

        if (results.isEmpty()) {
            return new ToolResult(null, "未找到匹配 " + pattern + " 的文件", false);
        }
        return new ToolResult(null, String.join("\n", results), false);
    }
}
