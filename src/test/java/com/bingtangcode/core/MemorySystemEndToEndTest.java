package com.bingtangcode.core;

import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.bingtangcode.core.SessionSerializer.JSONLRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MemorySystemEndToEndTest {

    private Path tempProjectRoot;
    private Path tempUserHome;
    private String originalUserHome;

    @BeforeEach
    public void setUp() throws IOException {
        tempProjectRoot = Files.createTempDirectory("bingtang_project");
        tempUserHome = Files.createTempDirectory("bingtang_user");
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempUserHome.toAbsolutePath().toString());
    }

    @AfterEach
    public void tearDown() throws IOException {
        System.setProperty("user.home", originalUserHome);
        deleteDirRecursive(tempProjectRoot);
        deleteDirRecursive(tempUserHome);
    }

    private void deleteDirRecursive(Path path) throws IOException {
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
    public void testInstructionLoaderNormalAndSandboxing() throws IOException {
        // 创建项目级与用户级指令文件
        Path projectRootFile = tempProjectRoot.resolve("BINGTANGCODE.md");
        Path projectLocalDir = tempProjectRoot.resolve(".bingtangcode");
        Files.createDirectories(projectLocalDir);
        Path projectLocalFile = projectLocalDir.resolve("BINGTANGCODE.md");

        Path userConfigDir = tempUserHome.resolve(".bingtangcode");
        Files.createDirectories(userConfigDir);
        Path userFile = userConfigDir.resolve("BINGTANGCODE.md");

        // 写入项目级内容并包含合法 include
        Files.writeString(projectRootFile, "# Project Root Instructions\n@include sub_instructions.md", StandardCharsets.UTF_8);
        Files.writeString(tempProjectRoot.resolve("sub_instructions.md"), "Sub Instructions Text", StandardCharsets.UTF_8);

        // 写入越权引用的 include (逃逸根目录)
        Path externalDir = Files.createTempDirectory("external_dir");
        Path externalFile = externalDir.resolve("external.md");
        Files.writeString(externalFile, "Secret Info", StandardCharsets.UTF_8);
        
        Files.writeString(projectLocalFile, "# Local Instructions\n@include " + externalFile.toAbsolutePath().toString(), StandardCharsets.UTF_8);

        // 写入用户级包含循环引用的 include 链
        Files.writeString(userFile, "# User Instructions\n@include user_sub.md", StandardCharsets.UTF_8);
        Path userSubFile = userConfigDir.resolve("user_sub.md");
        Files.writeString(userSubFile, "User Sub Text\n@include BINGTANGCODE.md", StandardCharsets.UTF_8); // 循环引用自己

        // 运行解析
        String result = InstructionLoader.loadInstructions(tempProjectRoot);

        // 验证拼接顺序与优先级
        assertTrue(result.contains("# Project Root Instructions"));
        assertTrue(result.contains("Sub Instructions Text"));
        assertTrue(result.contains("# Local Instructions"));
        assertTrue(result.contains("# User Instructions"));
        assertTrue(result.contains("User Sub Text"));

        // 验证越权安全沙箱拦截成功（输出警告注释而非抛异常）
        assertTrue(result.contains("<!-- @include 路径超出允许范围，已跳过:"));
        assertFalse(result.contains("Secret Info"));

        // 验证环路防死锁成功（检测出循环并输出注释）
        assertTrue(result.contains("<!-- @include 检测到循环引用，已跳过:"));

        deleteDirRecursive(externalDir);
    }

    @Test
    public void testSessionPersistenceAndDynamicListing() throws Exception {
        SessionPersister persister = new SessionPersister(tempProjectRoot);

        // 1. 新建会话 ID 格式符合规范
        String sessionId = "20260620-103000-efab";
        
        // 构造含有 ToolResult 的 USER 消息以验证拆分落盘
        List<ToolResult> toolResults = List.of(
                new ToolResult("t1", "Tool Result 1 Content", false),
                new ToolResult("t2", "Tool Result 2 Content", true)
        );
        Message userResultMsg = new Message(Role.USER, "", List.of(), toolResults);
        persister.appendMessage(sessionId, userResultMsg);

        // 构造第一个 USER 对话消息以验证标题计算 (超过 50 字)
        String longInput = "我希望能帮我编写一个简单的 Spring Boot 应用并且连接 PostgreSQL 数据库并进行配置";
        Message userMsg = new Message(Role.USER, longInput);
        persister.appendMessage(sessionId, userMsg);

        // 2. 动态加载并验证元数据
        List<SessionPersister.SessionMeta> list = persister.listSessions();
        assertEquals(1, list.size());
        SessionPersister.SessionMeta meta = list.get(0);
        assertEquals(sessionId, meta.id);
        
        // 标题为第一条 role=user 的内容，截断到 50 字符 (前 47 字符加上 "...")
        String expectedTitle = longInput.substring(0, 47) + "...";
        assertEquals(expectedTitle, meta.title);

        // 验证消息总数正确 (2 条 ToolResult + 1 条普通 USER 消息 = 3)
        assertEquals(3, meta.messageCount);
    }

    @Test
    public void testSessionRecoveryValidationAndTimeGap() throws Exception {
        SessionPersister persister = new SessionPersister(tempProjectRoot);
        String sessionId = "20260620-110000-bcde";

        // 1. 写入消息：正常 user 消息
        long baseTs = System.currentTimeMillis() - 8 * 60 * 60 * 1000; // 8 小时前
        SessionSerializer.JSONLRecord r1 = new SessionSerializer.JSONLRecord("user", "Hello", null, null, baseTs);
        
        // 2. 写入助理消息，包含 tool_use 但没有对应的 tool_result (模拟崩溃被截断)
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "text", "text", "好的，我来读取文件。"),
                Map.of("type", "tool_use", "id", "t_temp", "name", "read_file", "input", Map.of("path", "pom.xml"))
        );
        SessionSerializer.JSONLRecord r2 = new SessionSerializer.JSONLRecord("assistant", blocks, null, null, baseTs + 5000);
        
        // 3. 写入后续正常消息（但在未完备前不能被认定为合法）
        SessionSerializer.JSONLRecord r3 = new SessionSerializer.JSONLRecord("user", "Next Message", null, null, System.currentTimeMillis());

        ObjectMapper mapper = new ObjectMapper();
        Path sessionFile = tempProjectRoot.resolve(".bingtangcode").resolve("sessions").resolve(sessionId + ".jsonl");
        Files.createDirectories(sessionFile.getParent());
        
        Files.writeString(sessionFile, 
                mapper.writeValueAsString(r1) + "\n" + 
                mapper.writeValueAsString(r2) + "\n" + 
                mapper.writeValueAsString(r3) + "\n",
                StandardCharsets.UTF_8);

        // 4. 调用 SessionRecovery 进行加载和修复
        List<SessionSerializer.JSONLRecord> rawRecords = persister.loadSessionRecords(sessionId);
        List<JSONLRecord> validated = SessionRecovery.validateMessageChain(rawRecords);

        // 验证未闭合的 tool_use 消息链被安全截断，只剩第一条
        assertEquals(1, validated.size());
        assertEquals("Hello", validated.get(0).content);

        // 5. 制造一个包含 8 小时间隔的合法消息流，测试时间跨度提醒注入
        List<JSONLRecord> gapRecords = new ArrayList<>();
        gapRecords.add(new SessionSerializer.JSONLRecord("user", "Hello 1", null, null, baseTs));
        gapRecords.add(new SessionSerializer.JSONLRecord("assistant", "Hi 1", null, null, baseTs + 1000));
        gapRecords.add(new SessionSerializer.JSONLRecord("user", "Hello 2", null, null, baseTs + 8L * 60 * 60 * 1000 + 1000)); // 确切 8 小时差值

        List<JSONLRecord> gapResult = SessionRecovery.injectTimeGapReminders(gapRecords);
        // 验证插入了一条 user 角色时间间隔消息
        assertEquals(4, gapResult.size());
        JSONLRecord reminder = gapResult.get(2);
        assertEquals("user", reminder.role);
        assertTrue(((String)reminder.content).contains("[系统提示] 本会话已暂停 8 小时 0 分钟。"));
    }
}
