package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tool.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class AutoMemoryCollector {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "你是一个负责提炼对话记忆并管理长期记忆的助手。\n" +
            "你必须分析给定的对话历史，提取有长期价值的信息，并更新长期记忆索引文件。\n\n" +
            "【极其重要】你必须遵守以下规则：\n" +
            "1. 严禁调用任何工具。\n" +
            "2. 输出格式必须是纯 JSON，不能包裹在 ```json ... ``` 标签中，不能包含任何 markdown 格式标记，只能是纯 JSON 字符串。\n" +
            "3. 提取的笔记分类必须是以下四种之一：\n" +
            "   - \"user_preference\": 用户偏好\n" +
            "   - \"correction_feedback\": 纠正反馈\n" +
            "   - \"project_knowledge\": 项目知识\n" +
            "   - \"reference_material\": 参考资料\n" +
            "4. 笔记文件名为 <type>_<short_slug>.md，其中 slug 必须全部小写、以下划线分隔（例如 user_preference_terse_replies.md）。\n" +
            "5. 分开管理索引文件：\n" +
            "   - 项目级索引包含: 项目知识 (project_knowledge)、参考资料 (reference_material)\n" +
            "   - 用户级索引包含: 用户偏好 (user_preference)、纠正反馈 (correction_feedback)\n" +
            "6. 索引文件行数限制为 200 行，大小限制在 25KB 以内。请根据当前的完整索引，自行判断合并或淘汰旧条目，程序不主动裁剪。\n\n" +
            "JSON 输出结构如下：\n" +
            "{\n" +
            "  \"notes\": [\n" +
            "    {\n" +
            "      \"type\": \"user_preference\",\n" +
            "      \"slug\": \"terse_replies\",\n" +
            "      \"title\": \"简洁回复偏好\",\n" +
            "      \"content\": \"用户明确倾向于精简代码回答，不写多段总结。\"\n" +
            "    }\n" +
            "  ],\n" +
            "  \"updatedProjectIndex\": \"# Project Memory Index\\n...\",\n" +
            "  \"updatedUserIndex\": \"# User Memory Index\\n...\"\n" +
            "}";

    public static void triggerAsyncMemoryCollection(
            LLMProvider provider,
            List<Message> history,
            Path projectRoot) {
        
        CompletableFuture.runAsync(() -> {
            try {
                Path projectMemoryDir = projectRoot.resolve(".bingtangcode").resolve("memory");
                Path userHome = Paths.get(System.getProperty("user.home"));
                Path userMemoryDir = userHome.resolve(".bingtangcode").resolve("memory");

                Files.createDirectories(projectMemoryDir);
                Files.createDirectories(userMemoryDir);

                Path projectIndexFile = projectMemoryDir.resolve("MEMORY.md");
                Path userIndexFile = userMemoryDir.resolve("MEMORY.md");

                String currentProjectIndex = Files.exists(projectIndexFile) ? Files.readString(projectIndexFile, StandardCharsets.UTF_8) : "# Project Memory Index\n";
                String currentUserIndex = Files.exists(userIndexFile) ? Files.readString(userIndexFile, StandardCharsets.UTF_8) : "# User Memory Index\n";

                // 构建请求 Prompt
                StringBuilder userPrompt = new StringBuilder();
                userPrompt.append("【当前对话历史】\n========================================\n");
                for (Message msg : history) {
                    userPrompt.append("[").append(msg.role().name()).append("]: ").append(msg.content()).append("\n");
                }
                userPrompt.append("========================================\n\n");
                userPrompt.append("【当前项目级记忆索引 MEMORY.md】\n").append(currentProjectIndex).append("\n\n");
                userPrompt.append("【当前用户级记忆索引 MEMORY.md】\n").append(currentUserIndex).append("\n\n");
                userPrompt.append("请提取对话中的新记忆，并返回合并/淘汰后的最新索引内容。请严格遵守 JSON 格式输出规则。");

                List<Message> messages = new ArrayList<>();
                messages.add(new Message(Role.SYSTEM, SYSTEM_PROMPT));
                messages.add(new Message(Role.USER, userPrompt.toString()));

                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<Exception> streamError = new AtomicReference<>();
                StringBuilder textBuilder = new StringBuilder();

                StreamCallback callback = new StreamCallback() {
                    @Override public void onToken(String token) { textBuilder.append(token); }
                    @Override public void onReasoning(String token) {}
                    @Override public void onToolCall(ToolCall toolCall) {}
                    @Override public void onUsage(int inputTokens, int outputTokens) {}
                    @Override public void onComplete() { latch.countDown(); }
                    @Override public void onError(Exception e) { streamError.set(e); latch.countDown(); }
                };

                provider.streamChat(messages, List.of(), callback);
                latch.await();

                if (streamError.get() != null) {
                    throw streamError.get();
                }

                String rawJson = textBuilder.toString().trim();
                // 去除可能带有的 markdown code block 标记
                if (rawJson.startsWith("```")) {
                    int firstLineBreak = rawJson.indexOf('\n');
                    int lastBackticks = rawJson.lastIndexOf("```");
                    if (firstLineBreak != -1 && lastBackticks != -1 && lastBackticks > firstLineBreak) {
                        rawJson = rawJson.substring(firstLineBreak + 1, lastBackticks).trim();
                    }
                }

                Map<String, Object> response = mapper.readValue(rawJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                
                @SuppressWarnings("unchecked")
                List<Map<String, String>> notes = (List<Map<String, String>>) response.get("notes");
                String updatedProjectIndex = (String) response.get("updatedProjectIndex");
                String updatedUserIndex = (String) response.get("updatedUserIndex");

                // 1. 保存生成的各笔记 Markdown 文件
                if (notes != null) {
                    for (Map<String, String> note : notes) {
                        String type = note.get("type");
                        String slug = note.get("slug");
                        String title = note.get("title");
                        String content = note.get("content");

                        if (type == null || slug == null || title == null || content == null) {
                            continue;
                        }

                        // 格式化 slug 为小写和下划线
                        slug = slug.toLowerCase().replaceAll("[^a-z0-9]", "_").replaceAll("_+", "_");
                        String fileName = type + "_" + slug + ".md";
                        
                        // 决定存储目录：项目级 vs 用户级
                        Path targetDir = ("user_preference".equals(type) || "correction_feedback".equals(type)) ? userMemoryDir : projectMemoryDir;
                        Path noteFile = targetDir.resolve(fileName);

                        // 写入带 Frontmatter 的 Markdown
                        long now = System.currentTimeMillis() / 1000;
                        String markdownContent = "---\n" +
                                "category: " + mapCategoryChinese(type) + "\n" +
                                "title: " + title + "\n" +
                                "slug: " + type + "_" + slug + "\n" +
                                "ts: " + now + "\n" +
                                "---\n" +
                                content;

                        Files.writeString(noteFile, markdownContent, StandardCharsets.UTF_8);
                    }
                }

                // 2. 写入更新后的项目级与用户级 MEMORY.md 索引文件
                if (updatedProjectIndex != null && !updatedProjectIndex.isBlank()) {
                    Files.writeString(projectIndexFile, updatedProjectIndex, StandardCharsets.UTF_8);
                }
                if (updatedUserIndex != null && !updatedUserIndex.isBlank()) {
                    Files.writeString(userIndexFile, updatedUserIndex, StandardCharsets.UTF_8);
                }

            } catch (Exception e) {
                System.err.println("[系统记忆] 自动记忆提炼更新失败: " + e.getMessage());
            }
        });
    }

    private static String mapCategoryChinese(String type) {
        return switch (type) {
            case "user_preference" -> "用户偏好";
            case "correction_feedback" -> "纠正反馈";
            case "project_knowledge" -> "项目知识";
            case "reference_material" -> "参考资料";
            default -> type;
        };
    }
}
