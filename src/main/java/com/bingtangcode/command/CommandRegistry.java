package com.bingtangcode.command;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CommandRegistry {
    private final Map<String, Command> commands = new HashMap<>();
    private final Set<String> registeredNamesAndAliases = new HashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public void register(Command command) {
        lock.writeLock().lock();
        try {
            String lowerName = command.getName().toLowerCase();
            if (registeredNamesAndAliases.contains(lowerName)) {
                throw new IllegalStateException("命令名或别名冲突: " + lowerName);
            }
            for (String alias : command.getAliases()) {
                String lowerAlias = alias.toLowerCase();
                if (registeredNamesAndAliases.contains(lowerAlias)) {
                    throw new IllegalStateException("命令名或别名冲突: " + lowerAlias);
                }
            }
            
            commands.put(lowerName, command);
            registeredNamesAndAliases.add(lowerName);
            for (String alias : command.getAliases()) {
                registeredNamesAndAliases.add(alias.toLowerCase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Command find(String name) {
        lock.readLock().lock();
        try {
            String lower = name.toLowerCase();
            Command cmd = commands.get(lower);
            if (cmd != null) {
                return cmd;
            }
            // 检查别名
            for (Command c : commands.values()) {
                for (String alias : c.getAliases()) {
                    if (alias.equalsIgnoreCase(lower)) {
                        return c;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> complete(String prefix) {
        lock.readLock().lock();
        try {
            String cleanPrefix = prefix;
            if (cleanPrefix.startsWith("/")) {
                cleanPrefix = cleanPrefix.substring(1);
            }
            cleanPrefix = cleanPrefix.toLowerCase();
            
            Set<String> matches = new HashSet<>();
            for (Command cmd : commands.values()) {
                if (cmd.isHidden()) {
                    continue;
                }
                if (cmd.getName().toLowerCase().startsWith(cleanPrefix)) {
                    matches.add("/" + cmd.getName());
                }
            }
            List<String> list = new ArrayList<>(matches);
            Collections.sort(list);
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Command> getAllCommands() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(commands.values());
        } finally {
            lock.readLock().unlock();
        }
    }
}
