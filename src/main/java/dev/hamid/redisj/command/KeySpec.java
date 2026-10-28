package dev.hamid.redisj.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the key arguments of a command from its key positions, as reported by {@code COMMAND INFO}.
 */
public final class KeySpec {

    private KeySpec() {
    }

    /**
     * @param command the command
     * @param args    the call arguments
     * @return the key names at the command's key positions, in argument order
     */
    public static List<String> keysOf(Command command, Args args) {
        List<String> keys = new ArrayList<>();
        int first = command.firstKey();
        if (first <= 0) {
            return keys;
        }
        int last = command.lastKey() < 0 ? args.size() + command.lastKey() : command.lastKey();
        int step = Math.max(command.step(), 1);
        for (int i = first; i <= last && i < args.size(); i += step) {
            keys.add(args.key(i));
        }
        return keys;
    }
}
