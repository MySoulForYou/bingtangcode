package com.bingtangcode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathSandbox {

    private PathSandbox() {}

    /**
     * 验证给定文件路径是否在项目根目录内。
     * 解析所有符号链接后做前缀判断，越界则抛出 PathViolationException。
     */
    public static void validate(Path projectRoot, String filePath) {
        Path resolved = projectRoot.resolve(filePath).normalize();

        Path realProject;
        try {
            realProject = projectRoot.toRealPath();
        } catch (IOException e) {
            throw new PathViolationException("无法解析项目根目录的真实路径: " + e.getMessage());
        }

        Path realTarget;
        try {
            if (Files.exists(resolved)) {
                realTarget = resolved.toRealPath();
            } else {
                Path parent = resolved.getParent();
                if (parent != null && Files.exists(parent)) {
                    realTarget = parent.toRealPath().resolve(resolved.getFileName());
                } else {
                    // 父目录也不存在（如新建深层文件），逐级向上找存在的祖先
                    Path ancestor = resolved;
                    while (ancestor != null && !Files.exists(ancestor)) {
                        ancestor = ancestor.getParent();
                    }
                    if (ancestor == null) {
                        throw new PathViolationException("无法解析路径: " + filePath);
                    }
                    realTarget = ancestor.toRealPath().resolve(
                            ancestor.relativize(resolved));
                }
            }
        } catch (IOException e) {
            throw new PathViolationException("无法解析文件真实路径: " + filePath + " — " + e.getMessage());
        }

        if (!realTarget.startsWith(realProject)) {
            throw new PathViolationException("安全限制: 禁止访问项目目录以外的文件 — " + filePath);
        }
    }
}
