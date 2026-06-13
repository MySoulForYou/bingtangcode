package com.bingtangcode.permission;

import java.util.List;
import java.util.regex.Pattern;

public class Blacklist {

    private final List<Pattern> patterns;

    public Blacklist() {
        patterns = PatternHolder.PATTERNS;
    }

    public PermissionResult check(String command) {
        if (command == null || command.isBlank()) {
            return PermissionResult.allow();
        }
        for (Pattern p : patterns) {
            if (p.matcher(command).find()) {
                return PermissionResult.deny(
                        "匹配黑名单: " + p.pattern(), "blacklist");
            }
        }
        return PermissionResult.allow();
    }

    private static final class PatternHolder {
        static final List<Pattern> PATTERNS = List.of(
                // 递归强制删除根目录
                Pattern.compile("rm\\s+-rf\\s+/"),
                // 递归强制删除家目录
                Pattern.compile("rm\\s+-rf\\s+~"),
                // 递归强制删除当前目录所有内容
                Pattern.compile("rm\\s+-rf\\s+\\*"),
                // 提权执行（任意命令加 sudo 前缀）
                Pattern.compile("sudo\\s+"),
                // curl 管道到 shell 执行
                Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
                // wget 管道到 shell 执行
                Pattern.compile("wget\\s+.*-O\\s*-\\s*\\|\\s*(ba)?sh"),
                // 开放全部权限
                Pattern.compile("chmod\\s+777\\s+"),
                // 递归开放全部权限
                Pattern.compile("chmod\\s+-R\\s+777"),
                // 递归更改所有者
                Pattern.compile("chown\\s+-R\\s+"),
                // 格式化文件系统
                Pattern.compile("mkfs\\."),
                // 磁盘直接读写
                Pattern.compile("dd\\s+if="),
                // 覆盖磁盘设备
                Pattern.compile(">/dev/sd[a-z]"),
                // Fork 炸弹
                Pattern.compile(":(\\(\\)\\s*\\{|:&\\}\\s*;:)"),
                // 系统关机
                Pattern.compile("shutdown\\s+"),
                // 系统重启
                Pattern.compile("reboot\\s+"),
                // 切换运行级别
                Pattern.compile("init\\s+[0-6]"),
                // 停止/禁用系统服务
                Pattern.compile("systemctl\\s+(stop|disable|mask)\\s+"),
                // force push 到 main 分支
                Pattern.compile("git\\s+push\\s+--force\\s+.*main"),
                // force push 到 master 分支
                Pattern.compile("git\\s+push\\s+--force\\s+.*master")
        );
    }
}
