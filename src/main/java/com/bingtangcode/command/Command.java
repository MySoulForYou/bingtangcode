package com.bingtangcode.command;

import java.util.List;

public class Command {
    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;
    private final CommandType type;
    private final String argPrompt;
    private final boolean hidden;
    private final CommandHandler handler;

    public Command(String name, List<String> aliases, String description, String usage,
                   CommandType type, String argPrompt, boolean hidden, CommandHandler handler) {
        this.name = name;
        this.aliases = aliases;
        this.description = description;
        this.usage = usage;
        this.type = type;
        this.argPrompt = argPrompt;
        this.hidden = hidden;
        this.handler = handler;
    }

    public String getName() {
        return name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public String getDescription() {
        return description;
    }

    public String getUsage() {
        return usage;
    }

    public CommandType getType() {
        return type;
    }

    public String getArgPrompt() {
        return argPrompt;
    }

    public boolean isHidden() {
        return hidden;
    }

    public CommandHandler getHandler() {
        return handler;
    }
}
