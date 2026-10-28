package dev.hamid.redisj.command;

import dev.hamid.redisj.protocol.RespValue;

/**
 * Receives every successful write command, in execution order. Persistence implements this to
 * append to the append-only file.
 *
 * <p>Implementations run on the execution loop.
 */
public interface WriteLog {

    /** A log that records nothing. */
    WriteLog NONE = new WriteLog() {
        @Override
        public void recordWrite(CommandContext ctx, Command command, RespValue reply) {
        }
    };

    /**
     * Records a write command that just succeeded.
     *
     * @param ctx     the context the command ran in
     * @param command the write command
     * @param reply   the reply the command produced, which may carry a generated ID
     */
    void recordWrite(CommandContext ctx, Command command, RespValue reply);

    /**
     * Records that a key was evicted to free memory, so that replay removes it too.
     *
     * @param db  the database index
     * @param key the evicted key
     */
    default void recordEviction(int db, String key) {
    }

    /**
     * Periodic housekeeping, such as fsync. Called about every 100 ms. The default does nothing.
     */
    default void tick() {
    }
}
