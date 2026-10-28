package dev.hamid.redisj.command;

import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import dev.hamid.redisj.store.EvictionPolicy;
import dev.hamid.redisj.store.MemoryEstimator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Enforces {@code maxmemory} before each write command.
 *
 * <p>Usage is measured by scanning every key, so each write costs O(keys) while a limit is set.
 * That keeps the accounting simple and exact for the estimate, at the price of speed on large
 * datasets. Without a limit nothing is scanned.
 *
 * <p>Evictions are reported to the {@link WriteLog}, so the append-only file replays them.
 */
public final class Eviction {

    /** Commands that free memory or are never refused, even when the limit is reached. */
    private static final Set<String> ALWAYS_ALLOWED = Set.of("DEL", "UNLINK", "FLUSHDB", "FLUSHALL");

    private static final Random RANDOM = new Random();

    private Eviction() {
    }

    /**
     * Makes room for a write, evicting keys if the policy allows it.
     *
     * @param state       the server state
     * @param commandName the upper-case name of the write command
     * @return {@code true} if the write may run; {@code false} if the policy refuses it
     */
    public static boolean makeRoom(ServerState state, String commandName) {
        long limit = state.maxMemory();
        if (limit <= 0 || state.replaying() || ALWAYS_ALLOWED.contains(commandName)) {
            return true;
        }
        List<Candidate> candidates = new ArrayList<>();
        long used = 0;
        for (int index = 0; index < state.databases().count(); index++) {
            Database db = state.databases().get(index);
            for (String key : db.liveKeys()) {
                Entry entry = db.get(key);
                if (entry == null) {
                    continue;
                }
                long size = MemoryEstimator.estimate(key, entry);
                used += size;
                candidates.add(new Candidate(index, key, size));
            }
        }
        if (used <= limit) {
            return true;
        }
        if (state.evictionPolicy() == EvictionPolicy.NOEVICTION) {
            return false;
        }
        Collections.shuffle(candidates, RANDOM);
        for (Candidate candidate : candidates) {
            if (used <= limit) {
                break;
            }
            Database db = state.databases().get(candidate.db());
            if (db.remove(candidate.key())) {
                used -= candidate.size();
                state.writeLog().recordEviction(candidate.db(), candidate.key());
            }
        }
        return used <= limit;
    }

    private record Candidate(int db, String key, long size) {
    }
}
