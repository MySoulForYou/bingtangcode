package com.bingtangcode.command;

import java.util.List;

public interface UIController {
    void addSystemMessage(String text);
    void sendUserMessage(String text);
    void setPlanMode(boolean enabled);
    int getSessionInputTokens();
    int getSessionOutputTokens();
    int getSessionRoundCount();
    void refreshStatus();
    void clearScreen();
    int selectFromList(String title, List<String> items, int defaultIndex);
}
