package dev.hamid.redisj.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The set of logical databases of one server, all sharing one clock.
 */
public final class Databases {

    private final List<Database> databases;
    private final LongSupplier clock;

    /**
     * Creates the databases.
     *
     * @param count number of databases, at least 1
     * @param clock the time source in epoch milliseconds
     */
    public Databases(int count, LongSupplier clock) {
        if (count < 1) {
            throw new IllegalArgumentException("database count must be at least 1");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.databases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            databases.add(new Database(i, clock));
        }
    }

    /**
     * @param index the database index, from 0 to {@link #count()} - 1
     * @return the database
     */
    public Database get(int index) {
        return databases.get(index);
    }

    /**
     * @return the number of databases
     */
    public int count() {
        return databases.size();
    }

    /**
     * @return the current time in epoch milliseconds
     */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * @return the total number of stored keys across all databases
     */
    public long totalKeys() {
        long total = 0;
        for (Database db : databases) {
            total += db.size();
        }
        return total;
    }

    /**
     * Removes all keys from all databases.
     */
    public void flushAll() {
        for (Database db : databases) {
            db.clear();
        }
    }
}
