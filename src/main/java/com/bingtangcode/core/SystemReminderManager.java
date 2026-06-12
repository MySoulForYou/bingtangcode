package com.bingtangcode.core;

import com.bingtangcode.agent.AgentLoop;

public class SystemReminderManager {

    private static final String FULL_REMINDER =
            "<system-reminder>Plan模式：仅可用只读工具，专注于分析问题、制定计划，不要尝试修改文件或执行命令。</system-reminder>";

    private static final String SHORT_REMINDER =
            "<system-reminder>Plan模式：仅可用只读工具</system-reminder>";

    private AgentLoop.Mode currentMode = AgentLoop.Mode.FULL;
    private int roundCount = 0;
    private boolean firstRound = false;

    public void onModeSwitch(AgentLoop.Mode mode) {
        this.currentMode = mode;
        if (mode == AgentLoop.Mode.PLAN) {
            this.roundCount = 0;
            this.firstRound = true;
        } else {
            this.roundCount = 0;
            this.firstRound = false;
        }
    }

    public String getReminder() {
        if (currentMode == AgentLoop.Mode.FULL) {
            return null;
        }
        if (firstRound) {
            firstRound = false;
            return FULL_REMINDER;
        }
        if (roundCount % 3 == 0) {
            return FULL_REMINDER;
        }
        return SHORT_REMINDER;
    }

    public void onRoundComplete() {
        roundCount++;
    }
}
