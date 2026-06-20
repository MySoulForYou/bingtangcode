package com.bingtangcode.core;

import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionSerializer {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class JSONLRecord {
        public String role;
        public Object content;
        public String toolUseId;
        public Boolean isError;
        public long ts;

        public JSONLRecord() {}

        public JSONLRecord(String role, Object content, String toolUseId, Boolean isError, long ts) {
            this.role = role;
            this.content = content;
            this.toolUseId = toolUseId;
            this.isError = isError;
            this.ts = ts;
        }
    }

    /**
     * 将一条 Message 序列化为一行或多行 JSONL 字符串。
     * 由于一个 Message (USER) 中可能包含多个 ToolResult，
     * 此时需要按照规范拆分成多条 role="tool_result" 的 JSONL 记录。
     */
    public static List<String> serialize(Message message) throws Exception {
        List<String> lines = new ArrayList<>();
        long ts = System.currentTimeMillis();

        if (message.role() == Role.SYSTEM) {
            JSONLRecord rec = new JSONLRecord("system", message.content(), null, null, ts);
            lines.add(mapper.writeValueAsString(rec));
        } else if (message.role() == Role.USER) {
            if (message.toolResults() != null && !message.toolResults().isEmpty()) {
                for (ToolResult tr : message.toolResults()) {
                    JSONLRecord rec = new JSONLRecord("tool_result", tr.content(), tr.toolCallId(), tr.isError(), ts);
                    lines.add(mapper.writeValueAsString(rec));
                }
            } else {
                JSONLRecord rec = new JSONLRecord("user", message.content(), null, null, ts);
                lines.add(mapper.writeValueAsString(rec));
            }
        } else if (message.role() == Role.ASSISTANT) {
            Object contentField = message.content();
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                if (message.content() != null && !message.content().isEmpty()) {
                    Map<String, Object> textBlock = new HashMap<>();
                    textBlock.put("type", "text");
                    textBlock.put("text", message.content());
                    blocks.add(textBlock);
                }
                for (ToolCall tc : message.toolCalls()) {
                    Map<String, Object> toolBlock = new HashMap<>();
                    toolBlock.put("type", "tool_use");
                    toolBlock.put("id", tc.id());
                    toolBlock.put("name", tc.name());
                    toolBlock.put("input", tc.parameters());
                    blocks.add(toolBlock);
                }
                contentField = blocks;
            }
            JSONLRecord rec = new JSONLRecord("assistant", contentField, null, null, ts);
            lines.add(mapper.writeValueAsString(rec));
        }
        return lines;
    }

    /**
     * 将单行 JSONL 反序列化为临时的 JSONLRecord。
     */
    public static JSONLRecord deserializeLine(String line) throws Exception {
        return mapper.readValue(line, JSONLRecord.class);
    }

    /**
     * 将解析后的 JSONLRecord 列表，转换并合并为合法的 Dialogue 消息历史。
     * 合并相邻的 tool_result 成为单个 Message。
     */
    public static List<Message> convertRecordsToMessages(List<JSONLRecord> records) {
        List<Message> messages = new ArrayList<>();
        List<ToolResult> currentToolResults = new ArrayList<>();

        for (JSONLRecord rec : records) {
            if ("tool_result".equals(rec.role)) {
                boolean isErr = rec.isError != null && rec.isError;
                currentToolResults.add(new ToolResult(rec.toolUseId, (String) rec.content, isErr));
            } else {
                // 如果之前有累积的 tool_result，先提交
                if (!currentToolResults.isEmpty()) {
                    messages.add(new Message(Role.USER, "", List.of(), new ArrayList<>(currentToolResults)));
                    currentToolResults.clear();
                }

                if ("system".equals(rec.role)) {
                    messages.add(new Message(Role.SYSTEM, (String) rec.content));
                } else if ("user".equals(rec.role)) {
                    messages.add(new Message(Role.USER, (String) rec.content));
                } else if ("assistant".equals(rec.role)) {
                    if (rec.content instanceof String) {
                        messages.add(new Message(Role.ASSISTANT, (String) rec.content));
                    } else if (rec.content instanceof List) {
                        // 解析 content blocks
                        StringBuilder textBuilder = new StringBuilder();
                        List<ToolCall> toolCalls = new ArrayList<>();
                        List<?> blocks = (List<?>) rec.content;
                        for (Object blockObj : blocks) {
                            Map<String, Object> block = mapper.convertValue(blockObj, new TypeReference<Map<String, Object>>() {});
                            String type = (String) block.get("type");
                            if ("text".equals(type)) {
                                textBuilder.append(block.get("text"));
                            } else if ("tool_use".equals(type)) {
                                String id = (String) block.get("id");
                                String name = (String) block.get("name");
                                @SuppressWarnings("unchecked")
                                Map<String, Object> input = (Map<String, Object>) block.get("input");
                                toolCalls.add(new ToolCall(id, name, input));
                            }
                        }
                        messages.add(new Message(Role.ASSISTANT, textBuilder.toString(), toolCalls, List.of()));
                    } else {
                        // 降级保护
                        messages.add(new Message(Role.ASSISTANT, rec.content != null ? rec.content.toString() : ""));
                    }
                }
            }
        }

        // 扫尾工作
        if (!currentToolResults.isEmpty()) {
            messages.add(new Message(Role.USER, "", List.of(), new ArrayList<>(currentToolResults)));
        }

        return messages;
    }
}
