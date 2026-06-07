package com.bingtangcode.core;

import com.bingtangcode.llm.LLMProvider;
import com.bingtangcode.llm.StreamCallback;
import com.bingtangcode.tui.TerminalIO;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

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

            CountDownLatch latch = new CountDownLatch(1);
            boolean[] interrupted = {false};

            terminalIO.setInterruptHandler(() -> {
                interrupted[0] = true;
                cancelAction.run();
            });

            dialogue.streamResponse(provider, new StreamCallback() {
                @Override
                public void onToken(String token) {
                    terminalIO.printToken(token);
                }

                @Override
                public void onComplete() {
                    terminalIO.newline();
                    terminalIO.newline();
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    if (interrupted[0]) {
                        terminalIO.printInterrupted();
                    } else {
                        terminalIO.newline();
                        terminalIO.printError(e.getMessage());
                    }
                    terminalIO.newline();
                    latch.countDown();
                }
            });

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
