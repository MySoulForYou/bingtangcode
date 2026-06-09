package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.Message;
import com.bingtangcode.llm.Role;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolCall;
import com.bingtangcode.tool.ToolExecutor;
import com.bingtangcode.tool.ToolRegistry;
import com.bingtangcode.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DialogueManager {

    private final List<Message> history;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final List<Tool> tools;

    public DialogueManager(String systemPrompt, ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor, List<Tool> tools) {
        this.history = new ArrayList<>();
        this.history.add(new Message(Role.SYSTEM, systemPrompt));
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.tools = tools;
    }

    public void addUserMessage(String content) {
        history.add(new Message(Role.USER, content));
    }

    public void streamResponse(LLMProvider provider, StreamCallback callback) {
        doStream(provider, callback, false);
    }

    /**
     * 一次 streamChat 调用 + 可选工具执行。
     *
     * wrapper 的四个回调在这里承担不同的角色：
     *   onToken      → 收集文本 + 转发给 SessionManager 打印
     *   onToolCall   → 静默收集到 pendingToolCalls（可能一次回复调多个工具）
     *   onComplete   → 判断：有 toolCall 则切 handleToolCalls，无则正常写入 history 并结束
     *   onError      → 透传
     *
     * alreadyCalledTool: 防止无限循环——已执行过一次工具后，模型再调工具则直接丢弃并结束。
     *
     * 本方法在调用 provider.streamChat() 后立即返回（异步），所有回调在 provider 线程上触发。
     */
    private void doStream(LLMProvider provider, StreamCallback callback, boolean alreadyCalledTool) {
        StringBuilder sb = new StringBuilder();         // 收集本轮所有 onToken 文本
        List<ToolCall> pendingToolCalls = new ArrayList<>(); // 收集本轮所有 onToolCall

        StreamCallback wrapper = new StreamCallback() {
            @Override
            public void onToken(String token) {
                sb.append(token);
                callback.onToken(token);  // 转发给 SessionManager 流式打印
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                pendingToolCalls.add(toolCall);  // 静默收集，onComplete 时统一处理
            }

            @Override
            public void onComplete() {
                if (!pendingToolCalls.isEmpty()) {
                    // 模型调了工具 → 执行工具 → 结果回灌 → 递归 doStream
                    handleToolCalls(provider, callback, sb.toString(), pendingToolCalls, alreadyCalledTool);
                } else {
                    // 纯文本回复 → 写入历史 → 通知 SessionManager 本轮结束
                    history.add(new Message(Role.ASSISTANT, sb.toString()));
                    callback.onComplete();
                }
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        };

        // 异步提交，立即返回
        provider.streamChat(buildApiMessages(), tools, wrapper);
    }

    /**
     * 工具调用处理——四步往返：
     *   1. 写入 assistant(toolCalls) → 告诉 API"模型要调这些工具"
     *   2. ToolRegistry 查找 → ToolExecutor 执行 → 收集 ToolResult
     *   3. 写入 user(toolResults)   → 告诉 API"工具返回了这些结果"
     *   4. 递归 doStream(alreadyCalledTool=true) → 模型基于结果生成最终回复
     *
     * alreadyCalledTool 检查必须放在 history.add(ASSISTANT, toolCalls) 之前：
     *   如果先加了 assistant+toolCalls 再检查 alreadyCalledTool，
     *   拒绝时只有文本 user 消息跟上，API 看到 tool_calls 没有对应 tool_result → 400。
     *
     * provider 线程在此方法中阻塞（ToolExecutor.execute 里的 Future.get），
     * 工具执行完才继续。当前 provider 的 doStreamChat 还没返回，
     * 递归 doStream() 提交的第二轮任务排在 executor 队列里，等本轮返回后才执行。
     */
    private void handleToolCalls(LLMProvider provider, StreamCallback callback,
                                  String textPrefix, List<ToolCall> toolCalls, boolean alreadyCalledTool) {
        if (alreadyCalledTool) {
            // 已执行过工具，模型又返回 tool_call：
            // 不记录 toolCalls（避免 API 400），只保留文本直接结束
            history.add(new Message(Role.ASSISTANT, textPrefix));
            callback.onComplete();
            return;
        }

        // Step 1: 写入 assistant 消息（含 tool_calls）
        history.add(new Message(Role.ASSISTANT, textPrefix, toolCalls, List.of()));

        // Step 2: 逐个执行工具
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Tool tool = toolRegistry.get(tc.name());
            long start = System.currentTimeMillis();
            if (tool == null) {
                results.add(new ToolResult(tc.id(), "未找到工具: " + tc.name(), true));
            } else {
                results.add(toolExecutor.execute(tool, tc.id(), tc.parameters()));
            }
            long elapsed = System.currentTimeMillis() - start;

            // 终端显示工具调用进度
            String label = formatToolLabel(tc);
            String status = results.get(results.size() - 1).isError()
                    ? " \033[31m✗\033[0m"
                    : " \033[32m✓\033[0m";
            System.out.println("\033[90m  " + label + "\033[0m" + status + " \033[90m" + elapsed + "ms\033[0m");
        }

        // Step 3: 写入 user 消息（含 tool_results），回灌给 API
        history.add(new Message(Role.USER, "", List.of(), results));

        // Step 4: 递归——模型基于工具结果生成最终文本回复
        doStream(provider, callback, true);
    }

    /**
     * 清洗历史消息为 API 可接受格式。
     *   - SYSTEM 消息原样保留（各 Provider 自行决定放在哪）
     *   - 相邻同角色消息合并（API 不允许连续两条 ASSISTANT 或 USER，
     *     发生在 tool_result(USER) + 新用户输入(USER) 连续出现时）
     *   - 合并 content 文本的同时必须合并 toolCalls 和 toolResults 列表
     *
     * 最后一条必须是 USER——这是 Anthropic 的硬性要求。
     * 正常流程不会触发此异常（每次 buildApiMessages 调用前最后一条都是 user），
     * 它是一个断言，用于在发请求前抓住组装逻辑的 bug。
     */
    List<Message> buildApiMessages() {
        List<Message> cleaned = new ArrayList<>();
        for (Message msg : history) {
            if (msg.role() == Role.SYSTEM) {
                cleaned.add(msg);
                continue;
            }
            int size = cleaned.size();
            if (size > 0 && cleaned.get(size - 1).role() == msg.role() && msg.role() != Role.SYSTEM) {
                Message prev = cleaned.get(size - 1);
                // 合并 content + toolCalls + toolResults，三者缺一不可
                cleaned.set(size - 1, new Message(prev.role(),
                        prev.content() + "\n" + msg.content(),
                        merge(prev.toolCalls(), msg.toolCalls()),
                        merge(prev.toolResults(), msg.toolResults())));
            } else {
                cleaned.add(msg);
            }
        }
        // 断言：对话静止时不会触发（用户已发新消息 或 工具刚执行完回灌了 tool_result），
        // 只有在消息组装有 bug 时才会炸
        if (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).role() != Role.USER) {
            throw new IllegalStateException("对话历史最后一条必须是 USER 消息");
        }
        return cleaned;
    }

    /** 格式化工具调用为终端显示文本，提取关键参数高亮显示 */
    private static String formatToolLabel(ToolCall tc) {
        StringBuilder sb = new StringBuilder();
        sb.append(tc.name());

        Map<String, Object> params = tc.parameters();
        // 文件路径（filePath / path）
        if (params.containsKey("filePath")) {
            sb.append(" \033[33m").append(params.get("filePath")).append("\033[0m");
        } else if (params.containsKey("path")) {
            sb.append(" \033[33m").append(params.get("path")).append("\033[0m");
        }

        // 搜索关键词
        if (params.containsKey("query")) {
            sb.append(" \"").append(params.get("query")).append("\"");
        }

        // glob 模式
        if (params.containsKey("pattern")) {
            sb.append(" ").append(params.get("pattern"));
        }

        // shell 命令（截断过长命令）
        if (params.containsKey("command")) {
            String cmd = params.get("command").toString();
            if (cmd.length() > 60) {
                cmd = cmd.substring(0, 57) + "...";
            }
            sb.append(" `").append(cmd).append("`");
        }

        // 行号范围
        boolean hasStart = params.containsKey("startLine");
        boolean hasEnd = params.containsKey("endLine");
        if (hasStart || hasEnd) {
            sb.append(" L");
            sb.append(hasStart ? params.get("startLine").toString() : "1");
            sb.append("-");
            sb.append(hasEnd ? params.get("endLine").toString() : "end");
        }

        return sb.toString();
    }

    private static <T> List<T> merge(List<T> a, List<T> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<T> merged = new ArrayList<>(a);
        merged.addAll(b);
        return Collections.unmodifiableList(merged);
    }

    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }
}
