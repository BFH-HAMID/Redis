package dev.hamid.redisj.command;

import dev.hamid.redisj.protocol.RespValue;
import java.util.Set;

/**
 * A server command: its metadata plus its implementation.
 *
 * <p>The metadata follows Redis's {@code COMMAND INFO} model. {@link #arity()} is exact when
 * positive and a minimum when negative, counting the command name as one argument. Key positions
 * are 1-based, with {@code 0} meaning the command has no keys. A negative {@link #lastKey()} counts
 * from the end.
 */
public interface Command {

    /** Command flags, as reported by {@code COMMAND INFO}. */
    enum Flag {
        /** The command may modify the dataset. */
        WRITE,
        /** The command only reads the dataset. */
        READONLY,
        /** The command runs in constant or near-constant time. */
        FAST,
        /** The command is an administrative command. */
        ADMIN
    }

    /**
     * @return the upper-case command name, for example {@code "SET"}
     */
    String name();

    /**
     * @return the arity; positive means exact, negative means at least {@code -arity}
     */
    int arity();

    /**
     * @return the command flags
     */
    Set<Flag> flags();

    /**
     * @return the 1-based index of the first key, or 0 if none
     */
    int firstKey();

    /**
     * @return the 1-based index of the last key, or a negative value to count from the end
     */
    int lastKey();

    /**
     * @return the step between key arguments
     */
    int step();

    /**
     * Runs the command.
     *
     * @param context the call context
     * @return the reply, or {@code null} for no reply
     */
    RespValue execute(CommandContext context);

    /** Functional implementation of a command. */
    @FunctionalInterface
    interface Handler {

        /**
         * @param context the call context
         * @return the reply, or {@code null} for no reply
         */
        RespValue execute(CommandContext context);
    }

    /**
     * Creates a command from its metadata and a handler.
     *
     * @param name     upper-case name
     * @param arity    arity, as described in {@link Command}
     * @param flags    flags
     * @param firstKey first key position
     * @param lastKey  last key position
     * @param step     key step
     * @param handler  implementation
     * @return the command
     */
    static Command of(String name, int arity, Set<Flag> flags, int firstKey, int lastKey, int step,
                      Handler handler) {
        return new SimpleCommand(name, arity, Set.copyOf(flags), firstKey, lastKey, step, handler);
    }

    /**
     * A {@link Command} built from metadata and a {@link Handler}.
     *
     * @param name     upper-case name
     * @param arity    arity
     * @param flags    flags
     * @param firstKey first key position
     * @param lastKey  last key position
     * @param step     key step
     * @param handler  implementation
     */
    record SimpleCommand(String name, int arity, Set<Flag> flags, int firstKey, int lastKey, int step,
                         Handler handler) implements Command {

        @Override
        public RespValue execute(CommandContext context) {
            return handler.execute(context);
        }
    }
}
