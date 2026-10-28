package dev.hamid.redisj.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-client transaction state: whether {@code MULTI} is open, the queued commands, whether a
 * queued command was rejected, and the keys this client watches.
 *
 * <p>Used only from the execution loop.
 */
public final class ClientTransaction {

    /**
     * A command queued inside {@code MULTI}, with its validated arguments.
     *
     * @param command the command
     * @param args    the arguments, including the command name
     */
    public record Queued(Command command, Args args) {
    }

    private boolean inMulti;
    private boolean queueFailed;
    private boolean watchDirty;
    private final List<Queued> queue = new ArrayList<>();
    private final Set<DbKey> watched = new HashSet<>();

    /**
     * @return {@code true} between {@code MULTI} and {@code EXEC}/{@code DISCARD}
     */
    public boolean inMulti() {
        return inMulti;
    }

    /**
     * Opens a transaction.
     */
    public void begin() {
        inMulti = true;
        queueFailed = false;
        queue.clear();
    }

    /**
     * @param command the validated command
     * @param args    its arguments
     */
    public void enqueue(Command command, Args args) {
        queue.add(new Queued(command, args));
    }

    /**
     * Records that a command was rejected while queuing. {@code EXEC} then aborts.
     */
    public void markQueueFailed() {
        queueFailed = true;
    }

    /**
     * @return {@code true} if a queued command was rejected
     */
    public boolean queueFailed() {
        return queueFailed;
    }

    /**
     * Records that a watched key was modified.
     */
    public void markWatchDirty() {
        watchDirty = true;
    }

    /**
     * @return {@code true} if a watched key was modified since the watch was set
     */
    public boolean watchDirty() {
        return watchDirty;
    }

    /**
     * Closes the transaction and returns its queue. Does not touch watches.
     *
     * @return the queued commands in order
     */
    public List<Queued> takeQueue() {
        inMulti = false;
        queueFailed = false;
        List<Queued> out = List.copyOf(queue);
        queue.clear();
        return out;
    }

    /**
     * @return the keys currently watched by this client; mutable, owned by the watch table
     */
    public Set<DbKey> watched() {
        return watched;
    }

    /**
     * Forgets all watches and clears the dirty flag.
     */
    public void clearWatches() {
        watched.clear();
        watchDirty = false;
    }
}
