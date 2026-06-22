package com.bingtangcode.command;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class CommandSystemTest {

    @Test
    public void testRegistryConflictDetection() {
        CommandRegistry registry = new CommandRegistry();
        
        Command cmd1 = new Command("help", List.of("h"), "help desc", "/help", CommandType.LOCAL, "", false, ctx -> {});
        Command cmd2 = new Command("status", List.of("s", "h"), "status desc", "/status", CommandType.LOCAL, "", false, ctx -> {});

        registry.register(cmd1);
        
        // Conflict on alias "h"
        assertThrows(IllegalStateException.class, () -> registry.register(cmd2));
    }

    @Test
    public void testRegistryFindCaseInsensitive() {
        CommandRegistry registry = new CommandRegistry();
        Command cmd = new Command("help", List.of("h"), "desc", "/help", CommandType.LOCAL, "", false, ctx -> {});
        registry.register(cmd);

        assertNotNull(registry.find("HELP"));
        assertNotNull(registry.find("h"));
        assertNotNull(registry.find("H"));
        assertNull(registry.find("unknown"));
    }

    @Test
    public void testRegistryComplete() {
        CommandRegistry registry = new CommandRegistry();
        Command cmd1 = new Command("plan", List.of("p"), "p desc", "/plan", CommandType.LOCAL, "", false, ctx -> {});
        Command cmd2 = new Command("permission", List.of(), "perm desc", "/permission", CommandType.LOCAL, "", false, ctx -> {});
        Command cmdHidden = new Command("buddy", List.of(), "buddy desc", "/buddy", CommandType.LOCAL, "", true, ctx -> {});

        registry.register(cmd1);
        registry.register(cmd2);
        registry.register(cmdHidden);

        List<String> listP = registry.complete("p");
        assertEquals(2, listP.size());
        assertTrue(listP.contains("/plan"));
        assertTrue(listP.contains("/permission"));

        List<String> listB = registry.complete("b");
        // Hidden command "buddy" should not be completed
        assertTrue(listB.isEmpty());
    }

    @Test
    public void testCommandExecution() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        
        final boolean[] planModeCalled = {false};
        
        Command cmdPlan = new Command("plan", List.of("p"), "desc", "/plan", CommandType.LOCAL_UI, "", false, ctx -> {
            ctx.getUi().setPlanMode(true);
            planModeCalled[0] = true;
        });
        registry.register(cmdPlan);

        UIController mockUi = new UIController() {
            @Override public void addSystemMessage(String text) {}
            @Override public void sendUserMessage(String text) {}
            @Override public void setPlanMode(boolean enabled) {
                assertEquals(true, enabled);
            }
            @Override public int getSessionInputTokens() { return 0; }
            @Override public int getSessionOutputTokens() { return 0; }
            @Override public int getSessionRoundCount() { return 0; }
            @Override public void refreshStatus() {}
            @Override public void clearScreen() {}
            @Override public int selectFromList(String title, List<String> items, int defaultIndex) { return 0; }
        };

        CommandContext ctx = new CommandContext("", mockUi, null, null, null, registry);
        
        Command found = registry.find("plan");
        assertNotNull(found);
        found.getHandler().handle(ctx);
        
        assertTrue(planModeCalled[0]);
    }
}
