package dev.hamid.redisj.store;

/**
 * A key's value together with its expiry deadline.
 *
 * <p>Entries belong to one {@link Database} and are confined to the execution loop, so they are
 * not thread-safe. Values are {@code byte[]} for strings. Later phases add other value types.
 */
public final class Entry {

    /** Marker for a key without an expiry. */
    public static final long NO_EXPIRY = -1L;

    private Object value;
    private long expireAt = NO_EXPIRY;

    Entry(Object value) {
        this.value = value;
    }

    /**
     * @return the stored value
     */
    public Object value() {
        return value;
    }

    void setValue(Object value) {
        this.value = value;
    }

    /**
     * @return the absolute expiry time in epoch milliseconds, or {@link #NO_EXPIRY}
     */
    public long expireAt() {
        return expireAt;
    }

    void setExpireAt(long expireAt) {
        this.expireAt = expireAt;
    }

    /**
     * @return {@code true} if the key has an expiry deadline
     */
    public boolean hasExpiry() {
        return expireAt != NO_EXPIRY;
    }

    /**
     * @return the Redis type name of the value, as reported by {@code TYPE}
     */
    public String typeName() {
        if (value instanceof byte[]) {
            return "string";
        }
        if (value instanceof java.util.LinkedList<?>) {
            return "list";
        }
        if (value instanceof java.util.LinkedHashMap<?, ?>) {
            return "hash";
        }
        if (value instanceof java.util.LinkedHashSet<?>) {
            return "set";
        }
        return "unknown";
    }
}
