package dev.hamid.redisj.command;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps watched keys to the transactions watching them, so that a write can mark the affected
 * transactions dirty in constant time per watcher.
 *
 * <p>A key is considered modified when a successful command with the {@code WRITE} flag names it
 * as a key argument. {@code FLUSHDB} and {@code FLUSHALL} modify every watched key in the affected
 * databases. A key removed by lazy or active expiry does <em>not</em> dirty a watch; this is a known
 * difference from Redis.
 *
 * <p>Used only from the execution loop.
 */
public final class WatchTable {

    private final Map<DbKey, Set<ClientTransaction>> watchers = new HashMap<>();

    /**
     * Watches a key on behalf of a transaction.
     *
     * @param transaction the watching client's transaction
     * @param key         the key to watch
     */
    public void watch(ClientTransaction transaction, DbKey key) {
        watchers.computeIfAbsent(key, k -> new HashSet<>()).add(transaction);
        transaction.watched().add(key);
    }

    /**
     * Removes all watches of a transaction and clears its dirty flag.
     *
     * @param transaction the transaction
     */
    public void unwatchAll(ClientTransaction transaction) {
        for (DbKey key : transaction.watched()) {
            Set<ClientTransaction> set = watchers.get(key);
            if (set != null) {
                set.remove(transaction);
                if (set.isEmpty()) {
                    watchers.remove(key);
                }
            }
        }
        transaction.clearWatches();
    }

    /**
     * Marks the transactions that watch the keys modified by a successful write command.
     *
     * @param command the write command that ran
     * @param ctx     the context it ran in
     */
    public void markWritten(Command command, CommandContext ctx) {
        String name = command.name();
        int db = ctx.client().db();
        if (name.equals("FLUSHALL")) {
            touchAll();
            return;
        }
        if (name.equals("FLUSHDB")) {
            touchDb(db);
            return;
        }
        for (String key : KeySpec.keysOf(command, ctx.args())) {
            touch(db, key);
        }
    }

    private void touch(int db, String key) {
        Set<ClientTransaction> set = watchers.get(new DbKey(db, key));
        if (set != null) {
            for (ClientTransaction transaction : set) {
                transaction.markWatchDirty();
            }
        }
    }

    private void touchDb(int db) {
        for (Map.Entry<DbKey, Set<ClientTransaction>> entry : watchers.entrySet()) {
            if (entry.getKey().db() == db) {
                entry.getValue().forEach(ClientTransaction::markWatchDirty);
            }
        }
    }

    private void touchAll() {
        for (Set<ClientTransaction> set : watchers.values()) {
            set.forEach(ClientTransaction::markWatchDirty);
        }
    }
}
