package dev.hamid.redisj.command;

import dev.hamid.redisj.store.Database;

/**
 * Converts expiry arguments into absolute deadlines and applies them.
 *
 * <p>An absolute deadline at or before the current time deletes the key immediately, as Redis does.
 */
public final class Deadlines {

    private Deadlines() {
    }

    /**
     * Computes {@code base + amount * unitMillis}, rejecting overflow.
     *
     * @param amount     the user-supplied amount, may be negative
     * @param unitMillis milliseconds per unit (1000 for seconds, 1 for milliseconds)
     * @param base       the base in epoch milliseconds (the current time for relative values, 0 for absolute)
     * @param command    the lower-case command name, used in the error message
     * @return the deadline in epoch milliseconds
     * @throws CommandException if the result overflows
     */
    public static long deadline(long amount, long unitMillis, long base, String command) {
        try {
            return Math.addExact(base, Math.multiplyExact(amount, unitMillis));
        } catch (ArithmeticException e) {
            throw new CommandException("ERR invalid expire time in '" + command + "' command");
        }
    }

    /**
     * Applies a deadline to a key that exists. The caller must have checked the key exists.
     * A deadline at or before now deletes the key.
     *
     * @param db       the database
     * @param key      the key
     * @param deadline the deadline in epoch milliseconds
     */
    public static void apply(Database db, String key, long deadline) {
        if (deadline <= db.now()) {
            db.remove(key);
        } else {
            db.setExpireAt(key, deadline);
        }
    }
}
