package com.bingtangcode.tool.tools;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

public class ExecuteCommandTool implements Tool {

    private final Path projectRoot;
    private final Function<String, Boolean> confirmationHook;

    public ExecuteCommandTool(Path projectRoot, Function<String, Boolean> confirmationHook) {
        this.projectRoot = projectRoot;
        this.confirmationHook = confirmationHook;
    }

    @Override
    public String getName() {
        return "execute_command";
    }

    @Override
    public String getDescription() {
        return """
在项目根目录下执行一个 shell 命令，返回 stdout、stderr 和 exit code。\
适合运行构建命令（mvn、npm）、版本控制操作（git）、或脚本执行。\
命令执行前可能会弹出用户确认——如果确认被拒绝，会返回 isError 为 true 且内容为"用户取消了命令执行"的结果。\
exit code 非 0 会被视为执行失败（isError=true），stderr 有输出但不影响 exit code 时仍视为成功。""";

    }

    @Override
    public String getParametersSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "要执行的 shell 命令"}
          },
          "required": ["command"]
        }
        """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String command = (String) params.get("command");
        if (command == null || command.isBlank()) {
            return new ToolResult(null, "参数错误: command 不能为空", true);
        }

        if (confirmationHook != null && !confirmationHook.apply(command)) {
            return new ToolResult(null, "用户取消了命令执行", true);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(false);

            Process process = pb.start();

            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int exitCode = process.waitFor();

            StringBuilder result = new StringBuilder();
            if (!stdout.isEmpty()) {
                result.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append("[stderr]\n").append(stderr);
            }
            if (result.isEmpty()) {
                result.append("(无输出)");
            }
            result.append("\nexitCode=").append(exitCode);

            return new ToolResult(null, result.toString(), exitCode != 0);
        } catch (IOException e) {
            return new ToolResult(null, "命令执行失败: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(null, "命令执行被中断", true);
        }
    }
}
