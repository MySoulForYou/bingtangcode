package com.bingtangcode.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstructionLoader {

    private static final int MAX_DEPTH = 5;
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("^\\s*@include\\s+(.+)$");

    public static String loadInstructions(Path projectRoot) {
        List<String> segments = new ArrayList<>();

        // 优先级 1: 项目根目录 BINGTANGCODE.md
        Path projectRootFile = projectRoot.resolve("BINGTANGCODE.md");
        if (Files.exists(projectRootFile)) {
            segments.add(parseFile(projectRootFile, projectRoot, 1, new HashSet<>()));
        }

        // 优先级 2: 本地私有目录 .bingtangcode/BINGTANGCODE.md
        Path projectLocalFile = projectRoot.resolve(".bingtangcode").resolve("BINGTANGCODE.md");
        if (Files.exists(projectLocalFile)) {
            segments.add(parseFile(projectLocalFile, projectRoot, 1, new HashSet<>()));
        }

        // 优先级 3: 用户目录 ~/.bingtangcode/BINGTANGCODE.md
        Path userHome = Paths.get(System.getProperty("user.home"));
        Path userConfigDir = userHome.resolve(".bingtangcode");
        Path userFile = userConfigDir.resolve("BINGTANGCODE.md");
        if (Files.exists(userFile)) {
            segments.add(parseFile(userFile, userConfigDir, 1, new HashSet<>()));
        }

        return String.join("\n---\n", segments);
    }

    private static String parseFile(Path file, Path boundary, int depth, Set<Path> visited) {
        Path absoluteFile = file.toAbsolutePath().normalize();
        if (visited.contains(absoluteFile)) {
            return "<!-- @include 检测到循环引用，已跳过: " + boundary.relativize(absoluteFile).toString() + " -->";
        }
        if (depth > MAX_DEPTH) {
            return "<!-- @include 嵌套层级超出限制，已跳过 -->";
        }

        visited.add(absoluteFile);
        List<String> lines;
        try {
            lines = Files.readAllLines(absoluteFile);
        } catch (IOException e) {
            return "<!-- @include 无法读取文件: " + file.getFileName().toString() + " -->";
        }

        StringBuilder sb = new StringBuilder();
        Path currentDir = absoluteFile.getParent();

        for (String line : lines) {
            Matcher matcher = INCLUDE_PATTERN.matcher(line);
            if (matcher.matches()) {
                String includePathStr = matcher.group(1).trim();
                Path includePath = Paths.get(includePathStr);
                Path targetPath = currentDir.resolve(includePath).toAbsolutePath().normalize();
                Path normalizedBoundary = boundary.toAbsolutePath().normalize();

                if (!targetPath.startsWith(normalizedBoundary)) {
                    sb.append("<!-- @include 路径超出允许范围，已跳过: ").append(includePathStr).append(" -->\n");
                } else if (!Files.exists(targetPath)) {
                    sb.append("<!-- @include 文件不存在，已跳过: ").append(includePathStr).append(" -->\n");
                } else {
                    Set<Path> nextVisited = new HashSet<>(visited);
                    String childContent = parseFile(targetPath, boundary, depth + 1, nextVisited);
                    sb.append(childContent).append("\n");
                }
            } else {
                sb.append(line).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
