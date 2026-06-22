package com.bingtangcode.command;

import com.bingtangcode.agent.AgentLoop;
import com.bingtangcode.core.DialogueManager;
import com.bingtangcode.config.ConfigManager;

public class CommandContext {
    private final String args;
    private final UIController ui;
    private final DialogueManager dialogue;
    private final AgentLoop agentLoop;
    private final ConfigManager config;
    private final CommandRegistry registry;

    public CommandContext(String args, UIController ui, DialogueManager dialogue, AgentLoop agentLoop, ConfigManager config, CommandRegistry registry) {
        this.args = args;
        this.ui = ui;
        this.dialogue = dialogue;
        this.agentLoop = agentLoop;
        this.config = config;
        this.registry = registry;
    }

    public String getArgs() {
        return args;
    }

    public UIController getUi() {
        return ui;
    }

    public DialogueManager getDialogue() {
        return dialogue;
    }

    public AgentLoop getAgentLoop() {
        return agentLoop;
    }

    public ConfigManager getConfig() {
        return config;
    }

    public CommandRegistry getRegistry() {
        return registry;
    }
}
