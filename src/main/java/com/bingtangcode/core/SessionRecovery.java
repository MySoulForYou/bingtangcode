package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.core.SessionSerializer.JSONLRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class SessionRecovery {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long SIX_HOURS_MS = 6L * 60 * 60 * 1000;

    /**
     * 恢复会话的核心入口：进行格式清洗、一致性校验、时间跨度注入和 Token 超限就地压缩。
     */
    public static List<Message> recoverSession(
            String sessionId,
            List<JSONLRecord> rawRecords,
            DialogueManager dialogueManager,
            LLMProvider provider) throws Exception {

        if (rawRecords.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 验证消息链完整性（有呼必有应），丢弃有呼无应的尾部
        List<JSONLRecord> validated = validateMessageChain(rawRecords);

        // 2. 注入时间跨度提示
        List<JSONLRecord> withGaps = injectTimeGapReminders(validated);

        // 3. 转换为 Message 对象列表并合并相邻记录
        List<Message> messages = SessionSerializer.convertRecordsToMessages(withGaps);

        // 4. 判断是否需要就地历史压缩
        int estimatedTokens = estimateTokens(messages, dialogueManager);
        int threshold = dialogueManager.getContextWindow() - dialogueManager.getContextSummaryReserve() - dialogueManager.getContextManualCompressMargin();
        if (estimatedTokens >= threshold) {
            // 将历史临时导入一个临时的 DialogueManager，或者直接使用传入的 DialogueManager 进行压缩
            // 这里我们直接借助传入的 DialogueManager
            // 临时清空当前 history 并写入 messages，然后执行压缩
            List<Message> originalHistory = new ArrayList<>(dialogueManager.getHistory());
            try {
                // 暂时用我们加载的 messages 替换 DialogueManager 的 history
                dialogueManager.getHistory().clear();
                // 确保有 system prompt。如果加载的历史首条不是 SYSTEM，保留原 DialogueManager 的 system prompt
                if (messages.isEmpty() || messages.get(0).role() != com.bingtangcode.llm.Role.SYSTEM) {
                    dialogueManager.addMessage(originalHistory.get(0));
                }
                for (Message msg : messages) {
                    if (msg.role() == com.bingtangcode.llm.Role.SYSTEM) {
                        // 如果有 SYSTEM 消息，先不加（保持 DialogueManager 的 SYSTEM 消息唯一）
                        continue;
                    }
                    dialogueManager.addMessage(msg);
                }
                // 执行就地压缩
                dialogueManager.compressHistory(provider, true);
                messages = new ArrayList<>(dialogueManager.getHistory());
            } finally {
                // 恢复 DialogueManager 的历史
                dialogueManager.getHistory().clear();
                dialogueManager.getHistory().addAll(originalHistory);
            }
        }

        return messages;
    }

    /**
     * 校验工具调用链完整性，截断到最后一个完备（所有工具调用都已有结果）的位置。
     */
    public static List<JSONLRecord> validateMessageChain(List<JSONLRecord> records) {
        int lastValid = 0;
        Set<String> pendingToolUses = new HashSet<>();

        for (int i = 0; i < records.size(); i++) {
            JSONLRecord rec = records.get(i);
            if ("assistant".equals(rec.role)) {
                if (rec.content instanceof List) {
                    List<?> blocks = (List<?>) rec.content;
                    for (Object blockObj : blocks) {
                        try {
                            Map<String, Object> block = mapper.convertValue(blockObj, new TypeReference<Map<String, Object>>() {});
                            if ("tool_use".equals(block.get("type"))) {
                                String id = (String) block.get("id");
                                if (id != null) {
                                    pendingToolUses.add(id);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } else if ("tool_result".equals(rec.role)) {
                if (rec.toolUseId != null) {
                    pendingToolUses.remove(rec.toolUseId);
                }
            }

            if (pendingToolUses.isEmpty()) {
                lastValid = i + 1;
            }
        }

        if (lastValid == 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(records.subList(0, lastValid));
    }

    /**
     * 连续两条消息时间戳跨度超 6 小时，插入一条 user 消息提醒。
     */
    public static List<JSONLRecord> injectTimeGapReminders(List<JSONLRecord> records) {
        List<JSONLRecord> result = new ArrayList<>();
        if (records.isEmpty()) {
            return result;
        }

        result.add(records.get(0));
        for (int i = 1; i < records.size(); i++) {
            JSONLRecord prev = records.get(i - 1);
            JSONLRecord curr = records.get(i);

            long diffMs = curr.ts - prev.ts;
            if (diffMs >= SIX_HOURS_MS) {
                long hours = diffMs / (1000 * 60 * 60);
                long mins = (diffMs % (1000 * 60 * 60)) / (1000 * 60);
                String durationStr = hours + " 小时 " + mins + " 分钟";
                String msgContent = "[系统提示] 本会话已暂停 " + durationStr + "。部分上下文可能已过时，如需最新信息请重新读取相关文件。";
                
                // 插入一条 user 消息作为提醒
                JSONLRecord reminder = new JSONLRecord("user", msgContent, null, null, prev.ts + 1);
                result.add(reminder);
            }
            result.add(curr);
        }
        return result;
    }

    private static int estimateTokens(List<Message> messages, DialogueManager dm) {
        int totalChars = 0;
        for (Message msg : messages) {
            totalChars += dm.estimateMessageChars(msg);
        }
        return (int) Math.ceil(totalChars / dm.getContextCharToTokenRatio());
    }
}
