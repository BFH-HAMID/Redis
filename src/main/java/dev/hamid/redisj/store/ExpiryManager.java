package dev.hamid.redisj.store;

import java.util.Objects;
import java.util.Random;

/**
 * Runs Redis's active expiry cycle over all databases.
 *
 * <p>Each cycle samples {@value #SAMPLE_SIZE} keys with a TTL per database. If more than a quarter
 * of the sample was expired, the cycle repeats on that database, up to a 25 ms time budget. This
 * bounds the cost of expiry on the execution loop while still reclaiming memory from keys that are
 * never read again.
 */
public final class ExpiryManager {

    /** Keys sampled per iteration, as in Redis. */
    public static final int SAMPLE_SIZE = 20;

    private static final long BUDGET_NANOS = 25_000_000L;

    private final Databases databases;
    private final Random random;

    /**
     * @param databases the databases to sweep
     * @param random    the random source for sampling
     */
    public ExpiryManager(Databases databases, Random random) {
        this.databases = Objects.requireNonNull(databases, "databases");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Runs one active expiry cycle.
     *
     * @return the number of keys removed in this cycle
     */
    public long runActiveCycle() {
        long deadline = System.nanoTime() + BUDGET_NANOS;
        long removed = 0;
        for (int i = 0; i < databases.count(); i++) {
            Database db = databases.get(i);
            while (true) {
                Database.SampleResult result = db.activeExpireCycle(SAMPLE_SIZE, random);
                removed += result.expired();
                boolean mostlyLive = result.expired() * 4 <= result.sampled();
                if (result.sampled() == 0 || mostlyLive || System.nanoTime() > deadline) {
                    break;
                }
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        return removed;
    }
}
