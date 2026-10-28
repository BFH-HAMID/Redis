package dev.hamid.redisj.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * One logical Redis database: a key space plus the expiry index.
 *
 * <p>Expired keys are removed lazily when they are accessed through {@link #get(String)}. The
 * active expiry cycle ({@link #activeExpireCycle(int, Random)}) removes expired keys in the
 * background, sampling keys that have a TTL.
 *
 * <p>Keys are Java strings in which each char is one byte (ISO-8859-1), so keys stay binary safe.
 * The class is confined to the execution loop and is not thread-safe.
 */
public final class Database {

    /**
     * Outcome of one active-expiry sample.
     *
     * @param sampled number of keys inspected
     * @param expired number of those that were expired and removed
     */
    public record SampleResult(int sampled, int expired) {
    }

    private final int index;
    private final LongSupplier clock;
    private final Map<String, Entry> dict = new HashMap<>();
    private final ExpirySet expires = new ExpirySet();
    private long expiredKeys;

    Database(int index, LongSupplier clock) {
        this.index = index;
        this.clock = clock;
    }

    /**
     * @return the database index
     */
    public int index() {
        return index;
    }

    /**
     * @return the current time in epoch milliseconds, from the database clock
     */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * @return the number of stored keys, including expired keys that have not been reclaimed yet
     */
    public int size() {
        return dict.size();
    }

    /**
     * @return the number of keys that have a TTL
     */
    public int expiringKeys() {
        return expires.size();
    }

    /**
     * @return the total number of keys removed because they expired
     */
    public long expiredKeys() {
        return expiredKeys;
    }

    /**
     * Looks up a live key, removing it first if it has expired.
     *
     * @param key the key
     * @return the entry, or {@code null} if the key is missing or expired
     */
    public Entry get(String key) {
        Entry entry = dict.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            drop(key);
            expiredKeys++;
            return null;
        }
        return entry;
    }

    /**
     * Stores a value, creating the key or overwriting it.
     *
     * @param key     the key
     * @param value   the new value
     * @param keepTtl if {@code true}, an existing TTL is kept; otherwise it is cleared
     */
    public void put(String key, Object value, boolean keepTtl) {
        Entry entry = get(key);
        if (entry == null) {
            dict.put(key, new Entry(value));
            return;
        }
        entry.setValue(value);
        if (!keepTtl && entry.hasExpiry()) {
            entry.setExpireAt(Entry.NO_EXPIRY);
            expires.remove(key);
        }
    }

    /**
     * Removes a live key.
     *
     * @param key the key
     * @return {@code true} if a live key was removed
     */
    public boolean remove(String key) {
        if (get(key) == null) {
            return false;
        }
        drop(key);
        return true;
    }

    /**
     * Sets an absolute expiry on a live key.
     *
     * @param key the key
     * @param at  the expiry in epoch milliseconds
     * @return {@code true} if the key exists
     */
    public boolean setExpireAt(String key, long at) {
        Entry entry = get(key);
        if (entry == null) {
            return false;
        }
        entry.setExpireAt(at);
        if (at == Entry.NO_EXPIRY) {
            expires.remove(key);
        } else {
            expires.add(key);
        }
        return true;
    }

    /**
     * Removes the TTL of a live key.
     *
     * @param key the key
     * @return {@code true} if the key existed and had a TTL
     */
    public boolean persist(String key) {
        Entry entry = get(key);
        if (entry == null || !entry.hasExpiry()) {
            return false;
        }
        entry.setExpireAt(Entry.NO_EXPIRY);
        expires.remove(key);
        return true;
    }

    /**
     * Renames a key. The TTL moves with the value, and any existing target is overwritten.
     *
     * @param source the existing key
     * @param target the new name
     * @return {@code false} if the source key does not exist
     */
    public boolean rename(String source, String target) {
        Entry entry = get(source);
        if (entry == null) {
            return false;
        }
        if (source.equals(target)) {
            return true;
        }
        drop(target);
        drop(source);
        dict.put(target, entry);
        if (entry.hasExpiry()) {
            expires.add(target);
        }
        return true;
    }

    /**
     * Removes every key in this database.
     */
    public void clear() {
        dict.clear();
        expires.clear();
    }

    /**
     * @return the names of all keys that are not expired, in no particular order
     */
    public List<String> liveKeys() {
        List<String> out = new ArrayList<>(dict.size());
        for (Map.Entry<String, Entry> item : dict.entrySet()) {
            if (!isExpired(item.getValue())) {
                out.add(item.getKey());
            }
        }
        return out;
    }

    /**
     * Samples keys that have a TTL and removes the ones that have expired.
     *
     * @param samples maximum number of keys to sample
     * @param random  the random source
     * @return how many keys were sampled and how many were expired
     */
    public SampleResult activeExpireCycle(int samples, Random random) {
        int limit = Math.min(samples, expires.size());
        int sampled = 0;
        int expired = 0;
        for (int i = 0; i < limit; i++) {
            String key = expires.randomKey(random);
            if (key == null) {
                break;
            }
            sampled++;
            Entry entry = dict.get(key);
            if (entry == null) {
                expires.remove(key);
            } else if (isExpired(entry)) {
                drop(key);
                expiredKeys++;
                expired++;
            }
        }
        return new SampleResult(sampled, expired);
    }

    private boolean isExpired(Entry entry) {
        return entry.hasExpiry() && clock.getAsLong() > entry.expireAt();
    }

    private void drop(String key) {
        dict.remove(key);
        expires.remove(key);
    }
}
