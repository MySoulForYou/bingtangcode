package com.bingtangcode.tool;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行器——统一入口，对每次工具调用施加超时上限。
 *
 * 为什么开独立线程而不是直接调 tool.execute()?
 * 如果工具里写了死循环或 sleep 999, 直接调用会让调用方线程永远卡死。
 * 开独立线程后用 Future.get(30s) 阻塞等待——超时则强制中断，返回 isError=true.
 */
public class ToolExecutor {

    private final ExecutorService executor;
    private final long timeoutSeconds;

    public ToolExecutor(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 异步提交 + 同步等待: submit 把任务丢给 tool-executor 线程,
     * Future.get 阻塞调用方(provider 线程)直到工具完成或超时.
     */
    public ToolResult execute(Tool tool, String toolCallId, Map<String, Object> params) {
        Future<ToolResult> future = executor.submit(() -> tool.execute(params));

        try {
            ToolResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            // 注入 toolCallId, 与 API 的 tool_use_id 对应
            return new ToolResult(toolCallId, result.content(), result.isError());
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return new ToolResult(toolCallId, "工具执行超时(" + timeoutSeconds + "秒)", true);
        } catch (Exception e) {
            return new ToolResult(toolCallId, "执行失败: " + e.getMessage(), true);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
