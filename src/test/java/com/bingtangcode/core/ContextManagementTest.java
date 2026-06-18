package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ContextManagementTest {

    private Path tempSessionDir;
    private String sessionId = "test-session";
    private int contextWindow = 20000;

    @BeforeEach
    public void setUp() throws Exception {
        tempSessionDir = Paths.get(".bingtangcode", "sessions", sessionId);
        deleteDir(tempSessionDir);
    }

    @AfterEach
    public void tearDown() throws Exception {
        deleteDir(tempSessionDir);
    }

    private void deleteDir(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                 .sorted((a, b) -> b.compareTo(a))
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException ignored) {}
                 });
        }
    }

    @Test
    public void testSingleResultOverrun() throws Exception {
        DialogueManager dm = new DialogueManager(
                "System prompt", null, null, null, List.of(), sessionId, contextWindow, 50000, 200000);

        // 构造一个 60,000 字节的工具结果
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            sb.append("1234567890");
        }
        String bigContent = sb.toString();
        ToolResult tr = new ToolResult("call-big", bigContent, false);

        List<ToolResult> processed = dm.preventOverrunAndSave(List.of(tr));
        assertEquals(1, processed.size());
        ToolResult processedTr = processed.get(0);

        // 验证已落盘，内容被修改
        assertTrue(processedTr.content().contains("[工具执行结果过大已落盘保护]"));
        assertTrue(processedTr.content().contains("原始大小: 60000 字节"));
        assertTrue(processedTr.content().contains("保存路径: "));
        assertTrue(processedTr.content().contains("重读提示: "));

        // 验证文件实际写入
        Path file = tempSessionDir.resolve("tool-results").resolve("call-big");
        assertTrue(Files.exists(file));
        assertEquals(bigContent, Files.readString(file));
    }

    @Test
    public void testTotalResultOverrun() throws Exception {
        DialogueManager dm = new DialogueManager(
                "System prompt", null, null, null, List.of(), sessionId, contextWindow, 50000, 200000);

        // 构造5个工具结果，分别 48KB, 45KB, 42KB, 40KB, 35KB
        // 它们各自都小于 50KB 阈值，但合计 210,000 字节 (大于 200,000 字节合计阈值)
        String content48 = "a".repeat(48000);
        String content45 = "b".repeat(45000);
        String content42 = "c".repeat(42000);
        String content40 = "d".repeat(40000);
        String content35 = "e".repeat(35000);

        ToolResult tr48 = new ToolResult("call-48", content48, false);
        ToolResult tr45 = new ToolResult("call-45", content45, false);
        ToolResult tr42 = new ToolResult("call-42", content42, false);
        ToolResult tr40 = new ToolResult("call-40", content40, false);
        ToolResult tr35 = new ToolResult("call-35", content35, false);

        List<ToolResult> processed = dm.preventOverrunAndSave(List.of(tr48, tr45, tr42, tr40, tr35));
        assertEquals(5, processed.size());

        // 最大的是 48KB，应该被落盘替换；
        // 45KB + 42KB + 40KB + 35KB = 162KB <= 200,000 字节，它们应该保持原样
        assertTrue(processed.get(0).content().contains("[工具执行结果过大已落盘保护]"));
        assertEquals(content45, processed.get(1).content());
        assertEquals(content42, processed.get(2).content());
        assertEquals(content40, processed.get(3).content());
        assertEquals(content35, processed.get(4).content());

        // 验证 48KB 对应的文件已被创建
        assertTrue(Files.exists(tempSessionDir.resolve("tool-results").resolve("call-48")));
        assertFalse(Files.exists(tempSessionDir.resolve("tool-results").resolve("call-45")));
        assertFalse(Files.exists(tempSessionDir.resolve("tool-results").resolve("call-42")));
    }

    @Test
    public void testStripDraft() {
        String rawText = "<draft>\n思考草稿：这是分析过程...\n</draft>\n\n## 1 主要请求和意图\n用户想看 pom.xml";
        String cleaned = ContextCompressor.stripDraft(rawText);
        assertEquals("## 1 主要请求和意图\n用户想看 pom.xml", cleaned);

        // 没有 draft 标签的情况
        String normalText = "## 1 主要请求和意图\n用户想看 pom.xml";
        assertEquals(normalText, ContextCompressor.stripDraft(normalText));
    }

    @Test
    public void testTokenEstimator() {
        DialogueManager dm = new DialogueManager(
                "System", null, null, null, List.of(), sessionId, contextWindow, 50000, 200000);

        // 1. 测试全量估算
        dm.addUserMessage("Hello world"); // 11个字符
        int estimate = dm.estimateCurrentTokens();
        // (System(6) + User("Hello world")(11)) = 17 chars / 3.5 = 5
        assertEquals(5, estimate);

        // 2. 测试锚定真实值后的增量估算
        dm.lastApiInputTokens = 100;
        dm.lastApiOutputTokens = 50;
        dm.lastApiHistorySize = dm.getHistory().size(); // 此时包含 System 和 User 消息，共 2 条

        // 模拟助理返回的消息 (索引为 lastApiHistorySize，应被估算器跳过)
        dm.addMessage(new Message(Role.ASSISTANT, "Ok, I will help you.")); 
        // 模拟新增的用户输入 (15个字符，作为增量)
        dm.addMessage(new Message(Role.USER, "Another request"));

        int newEstimate = dm.estimateCurrentTokens();
        // 预期基准: 100 (input) + 50 (output) = 150
        // 增量字符: "Another request" (15 chars)
        // 增量估算: ceil(15 / 3.5) = 5
        // 最终预期: 150 + 5 = 155
        assertEquals(155, newEstimate);
    }

    @Test
    public void testCompressHistory() throws Exception {
        DialogueManager dm = new DialogueManager(
                "System prompt", null, null, null, List.of(), sessionId, contextWindow, 50000, 200000);

        // 插入大量对话使其可以被压缩
        // 我们要保留：累计估算 token >= 10000 且保留条数 >= 5
        // 我们构造 10 条每条 4000 字符的消息
        for (int i = 1; i <= 10; i++) {
            if (i % 2 == 1) {
                dm.addMessage(new Message(Role.USER, "USER-MSG-" + i + "-" + "x".repeat(4000)));
            } else {
                dm.addMessage(new Message(Role.ASSISTANT, "ASSISTANT-MSG-" + i + "-" + "y".repeat(4000)));
            }
        }

        // 构造 Mock LLMProvider 返回摘要结果
        LLMProvider mockProvider = new LLMProvider() {
            @Override
            public void streamChat(List<Message> messages, List<Tool> tools, StreamCallback callback) {
                // 验证我们没有传入 tools
                assertTrue(tools == null || tools.isEmpty());
                // 验证发送的消息中包含特殊的 SYSTEM_PROMPT
                assertTrue(messages.get(0).content().contains("你是一个专门负责生成对话历史结构化摘要的助手"));

                callback.onToken("<draft>草稿内容...</draft>\n\n");
                callback.onToken("## 1 主要请求和意图\n主要意图说明\n");
                callback.onToken("## 2 关键技术概念\n关键概念说明\n");
                callback.onToken("## 3 文件和代码段\n无\n");
                callback.onToken("## 4 错误和修复\n无\n");
                callback.onToken("## 5 问题解决过程\n无\n");
                callback.onToken("## 6 所有用户消息原文\nUSER-MSG-1\nUSER-MSG-3\n");
                callback.onToken("## 7 待办任务\n无\n");
                callback.onToken("## 8 当前工作\n无\n");
                callback.onToken("## 9 可能的下一步\n继续测试");
                callback.onComplete();
            }

            @Override
            public void shutdown() {}

            @Override
            public String getName() {
                return "MockProvider";
            }
        };

        // 执行手动压缩
        dm.compressHistory(mockProvider, true);

        List<Message> newHistory = dm.getHistory();
        // 压缩后的历史应该是：
        // 索引 0: System Prompt
        // 索引 1: USER role 摘要消息 (包含了正式摘要 + 边界提示)
        // 随后: 保留下来的近期消息
        assertTrue(newHistory.size() > 2);
        assertEquals(Role.SYSTEM, newHistory.get(0).role());
        assertEquals(Role.USER, newHistory.get(1).role());

        String summaryContent = newHistory.get(1).content();
        assertFalse(summaryContent.contains("<draft>"));
        assertTrue(summaryContent.contains("## 1 主要请求和意图"));
        assertTrue(summaryContent.contains("📖 重要提示："));
        assertTrue(summaryContent.contains("ReadFile"));
    }

    @Test
    public void testCustomConfiguredParameters() throws Exception {
        int customSummaryReserve = 5000;
        int customAutoMargin = 2000;
        int customManualMargin = 1000;
        int customKeepTokens = 5000;
        int customKeepMessages = 3;
        int customMaxCompressFailures = 2;
        double customCharToTokenRatio = 2.0;
        int customPreviewLimit = 50;
        int customPreviewLines = 3;

        DialogueManager dm = new DialogueManager(
                "System prompt", null, null, null, List.of(), sessionId, contextWindow, 50000, 200000,
                customSummaryReserve, customAutoMargin, customManualMargin, customKeepTokens,
                customKeepMessages, customMaxCompressFailures, customCharToTokenRatio,
                customPreviewLimit, customPreviewLines
        );

        // 1. 验证自定义的 Token/字符估算比率（2.0）
        dm.addUserMessage("1234567890"); // 10个字符
        // (System(13) + User("1234567890")(10)) = 23 chars / 2.0 = 12 tokens
        assertEquals(12, dm.estimateCurrentTokens());

        // 2. 验证自定义的预览行数（3 行）和长度（50 字节）
        String bigResult = "line1\nline2\nline3\nline4\nline5\n";
        ToolResult tr = new ToolResult("call-custom-preview", bigResult, false);
        ToolResult processed = dm.saveAndReplaceResult(tr, bigResult.getBytes().length);
        
        String content = processed.content();
        assertTrue(content.contains("line1\nline2\nline3"));
        assertFalse(content.contains("line4"));
    }
}
