package com.bingtangcode.core;

import com.bingtangcode.agent.AgentLoop;
import com.bingtangcode.tui.TerminalIO;

import java.nio.file.Paths;


public class SessionManager {

    private final TerminalIO terminalIO;
    private final AgentLoop agentLoop;
    private final Runnable cancelAction;

    public SessionManager(TerminalIO terminalIO, AgentLoop agentLoop, Runnable cancelAction) {
        this.terminalIO = terminalIO;
        this.agentLoop = agentLoop;
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

            if ("/plan".equals(input)) {
                agentLoop.setMode(AgentLoop.Mode.PLAN);
                System.out.println("\033[90m已切换到 Plan Mode，仅可用只读工具\033[0m");
                continue;
            }
            if ("/do".equals(input)) {
                agentLoop.setMode(AgentLoop.Mode.FULL);
                System.out.println("\033[90m已切换到 Do Mode，全工具可用\033[0m");
                continue;
            }

            terminalIO.printAssistantPrefix();

            terminalIO.setInterruptHandler(() -> {
                agentLoop.cancel();
                cancelAction.run();
            });

            agentLoop.run(input);

            terminalIO.setInterruptHandler(null);
        }

        terminalIO.shutdown();
    }

    private void printWelcome() {
        String workDir = shortenPath(Paths.get("").toAbsolutePath().toString());

        terminalIO.printTopBorder();
        terminalIO.printBorderLine("  /) /)  bingtangCode v0.3.0");
        terminalIO.printBorderLine(" ( T_T)  " + workDir);
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
