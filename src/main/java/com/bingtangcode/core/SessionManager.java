package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tui.TerminalIO;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;


public class SessionManager {

    private final TerminalIO terminalIO;
    private final DialogueManager dialogue;
    private final LLMProvider provider;
    private final Runnable cancelAction;

    public SessionManager(TerminalIO terminalIO, DialogueManager dialogue,
                          LLMProvider provider, Runnable cancelAction) {
        this.terminalIO = terminalIO;
        this.dialogue = dialogue;
        this.provider = provider;
        this.cancelAction = cancelAction;
    }

    /**
     * 主循环：读输入 → 发请求 → 等结果 → 循环。
     *
     * 线程分工：
     *   主线程（这里）:      读输入，发 streamChat，latch.await() 阻塞等待
     *   provider 读流线程:   跑 SSE 解析，回调 onToken/onToolCall/onComplete
     *   tool-executor 线程:  工具超时执行（仅在模型调用工具时启动）
     *
     * latch 用于主线程等待 provider 线程完成——onComplete 或 onError 时 countDown。
     */
    public void start() {
        printWelcome();

        while (true) {
            String input;
            try {
                input = terminalIO.readLine("> ");
                if (input == null) {
                    break;
                }
            } catch (Exception e) {
                continue;
            }

            input = input.trim();

            if ("/exit".equals(input) || "/quit".equals(input)) {
                break;
            }
            if (input.isEmpty()) {
                continue;
            }

            dialogue.addUserMessage(input);
            terminalIO.printAssistantPrefix();

            // latch 同步主线程和 provider 线程：
            // 主线程在这里 latch.await() 阻塞
            // provider 线程在 onComplete/onError 时 latch.countDown() 唤醒主线程
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean(false);
            terminalIO.setInterruptHandler(() -> {
                interrupted.set(true);
                cancelAction.run();
            });

            dialogue.streamResponse(provider, new StreamCallback() {
                @Override
                public void onToken(String token) {
                    terminalIO.printToken(token);  // 逐字流式打印到终端
                }

                @Override
                public void onComplete() {
                    // 所有回合结束（可能是纯文本 or 工具调用往返），唤醒主线程
                    terminalIO.newline();
                    terminalIO.newline();
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    if (interrupted.get()) {
                        terminalIO.printInterrupted();
                    } else {
                        terminalIO.newline();
                        terminalIO.printError(e.getMessage());
                    }
                    terminalIO.newline();
                    latch.countDown();
                }
            });

            // streamResponse 已返回（异步提交了任务到 provider 线程），
            // 主线程在这里等待直到 provider 线程完成所有工作
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        terminalIO.shutdown();
    }

    private void printWelcome() {
        String workDir = shortenPath(Paths.get("").toAbsolutePath().toString());

        terminalIO.printTopBorder();
        terminalIO.printBorderLine("  /) /)  bingtangCode v0.1.0");
        terminalIO.printBorderLine(" ( T_T)  " + provider.getName() + " · " + workDir);
        terminalIO.printBorderLine(" c(\")(\") /help 查看帮助  ·  /exit 退出");
        terminalIO.printBottomBorder();
        System.out.println();
    }

    private static String shortenPath(String path) {
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }
}
