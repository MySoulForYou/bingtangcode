package com.bingtangcode.core;

import com.bingtangcode.llm.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SessionPersister {

    private final Path sessionsDir;
    private static final SimpleDateFormat sessionDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");

    public static class SessionMeta {
        public String id;
        public Date createdAt;
        public Date lastActiveAt;
        public String title;
        public int messageCount;

        public SessionMeta(String id, Date createdAt, Date lastActiveAt, String title, int messageCount) {
            this.id = id;
            this.createdAt = createdAt;
            this.lastActiveAt = lastActiveAt;
            this.title = title;
            this.messageCount = messageCount;
        }
    }

    public SessionPersister(Path projectRoot) {
        this.sessionsDir = projectRoot.resolve(".bingtangcode").resolve("sessions");
        try {
            Files.createDirectories(this.sessionsDir);
        } catch (IOException e) {
            System.err.println("[警告] 无法创建会话存储目录: " + e.getMessage());
        }
    }

    /**
     * 将单条 Message 追加写入对应的 JSONL 文件。
     */
    public synchronized void appendMessage(String sessionId, Message message) {
        Path sessionFile = sessionsDir.resolve(sessionId + ".jsonl");
        try {
            List<String> lines = SessionSerializer.serialize(message);
            if (lines.isEmpty()) {
                return;
            }
            List<String> linesToWrite = new ArrayList<>();
            for (String line : lines) {
                linesToWrite.add(line);
            }
            Files.write(sessionFile, linesToWrite, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[错误] 追加写入会话历史失败: " + e.getMessage());
        }
    }

    /**
     * 加载历史会话的所有记录行。
     */
    public List<SessionSerializer.JSONLRecord> loadSessionRecords(String sessionId) throws IOException {
        Path sessionFile = sessionsDir.resolve(sessionId + ".jsonl");
        if (!Files.exists(sessionFile)) {
            return Collections.emptyList();
        }

        List<SessionSerializer.JSONLRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    SessionSerializer.JSONLRecord rec = SessionSerializer.deserializeLine(line);
                    records.add(rec);
                } catch (Exception e) {
                    // 静默跳过坏行
                }
            }
        }
        return records;
    }

    /**
     * 扫描会话目录并动态计算所有会话的元数据列表（排除了过期被删的会话）。
     */
    public List<SessionMeta> listSessions() {
        List<SessionMeta> list = new ArrayList<>();
        if (!Files.exists(sessionsDir)) {
            return list;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".jsonl".length());

                // 解析创建时间
                Date createdAt = parseSessionIdDate(id);
                if (createdAt == null) {
                    continue;
                }

                // 扫描文件动态计算标题、最后活跃时间及消息数
                String title = "空会话";
                Date lastActiveAt = createdAt;
                int messageCount = 0;

                try {
                    List<String> lines = Files.readAllLines(entry, StandardCharsets.UTF_8);
                    messageCount = lines.size();
                    String firstUserContent = null;
                    String lastLine = null;

                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        lastLine = line;
                        if (firstUserContent == null) {
                            try {
                                SessionSerializer.JSONLRecord rec = SessionSerializer.deserializeLine(line);
                                if ("user".equals(rec.role) && rec.content instanceof String) {
                                    firstUserContent = (String) rec.content;
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (firstUserContent != null) {
                        title = truncateTitle(firstUserContent);
                    }

                    if (lastLine != null) {
                        try {
                            SessionSerializer.JSONLRecord rec = SessionSerializer.deserializeLine(lastLine);
                            if (rec.ts > 0) {
                                lastActiveAt = new Date(rec.ts);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (IOException e) {
                    // 读取失败时，使用默认值
                }

                list.add(new SessionMeta(id, createdAt, lastActiveAt, title, messageCount));
            }
        } catch (IOException e) {
            System.err.println("[错误] 扫描会话列表失败: " + e.getMessage());
        }

        // 按最后活跃时间降序排列
        list.sort((a, b) -> b.lastActiveAt.compareTo(a.lastActiveAt));
        return list;
    }

    /**
     * 启动时后台异步执行 30 天过期会话自动清理。
     */
    public void startAsyncCleanup() {
        CompletableFuture.runAsync(() -> {
            try {
                long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
                long now = System.currentTimeMillis();

                if (!Files.exists(sessionsDir)) {
                    return;
                }

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
                    for (Path entry : stream) {
                        String fileName = entry.getFileName().toString();
                        String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                        Date createdAt = parseSessionIdDate(id);
                        if (createdAt != null) {
                            if (now - createdAt.getTime() > thirtyDaysMs) {
                                // 删除会话文件
                                Files.deleteIfExists(entry);
                                // 级联删除对应的工具结果子目录
                                Path sessionDir = sessionsDir.resolve(id);
                                deleteDirRecursive(sessionDir);
                                System.out.println("[系统清理] 已自动清理 30 天前的过期会话: " + id);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[系统清理] 过期会话清理失败: " + e.getMessage());
            }
        });
    }

    private Date parseSessionIdDate(String id) {
        if (id.length() < 15) {
            return null;
        }
        String datePart = id.substring(0, 15); // yyyyMMdd-HHmmss
        try {
            synchronized (sessionDateFormat) {
                return sessionDateFormat.parse(datePart);
            }
        } catch (ParseException e) {
            return null;
        }
    }

    private String truncateTitle(String content) {
        if (content.length() <= 50) {
            return content;
        }
        return content.substring(0, 47) + "...";
    }

    private void deleteDirRecursive(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }
}
