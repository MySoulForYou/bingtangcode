package com.bingtangcode.command;

public interface CommandHandler {
    void handle(CommandContext ctx) throws Exception;
}
