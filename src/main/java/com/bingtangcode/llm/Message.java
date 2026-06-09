package com.bingtangcode.llm;

import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolResult;

import java.util.List;

/**
 * 一条对话消息，内部始终用 String content + List toolCalls + List toolResults 扁平存储。
 *
 * 三种实际形态：
 *   纯文本:   content="帮我读 pom.xml",        toolCalls=[], toolResults=[]
 *   含工具调用: content="",                     toolCalls=[read_file], toolResults=[]
 *   含工具结果: content="",                     toolCalls=[], toolResults=[文件内容]
 *
 * API 边界层（各 Provider 的 buildApiMessage）负责将扁平字段拼装为 API 要求的嵌套格式。
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults) {

    /** 纯文本消息的便捷构造器 */
    public Message(Role role, String content) {
        this(role, content, List.of(), List.of());
    }
}
