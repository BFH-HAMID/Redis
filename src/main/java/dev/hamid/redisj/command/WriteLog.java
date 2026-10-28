package dev.hamid.redisj.command;

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
        public void recordWrite(CommandContext ctx, Command command) {
        }
    };

    /**
     * Records a write command that just succeeded.
     *
     * @param ctx     the context the command ran in
     * @param command the write command
     */
    void recordWrite(CommandContext ctx, Command command);

    /**
     * Periodic housekeeping, such as fsync. Called about every 100 ms. The default does nothing.
     */
    default void tick() {
    }
}
