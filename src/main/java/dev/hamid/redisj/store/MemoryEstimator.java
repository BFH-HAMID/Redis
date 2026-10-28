package dev.hamid.redisj.store;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Approximates the memory a key uses, for {@code maxmemory} accounting. The figures are rough
 * estimates of payload plus per-object overhead, not exact JVM sizes.
 */
public final class MemoryEstimator {

    private static final long KEY_OVERHEAD = 64;

    private MemoryEstimator() {
    }

    /**
     * @param key   the key name
     * @param entry the live entry
     * @return the estimated size in bytes
     */
    public static long estimate(String key, Entry entry) {
        return KEY_OVERHEAD + key.length() + valueSize(entry.value());
    }

    private static long valueSize(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.length + 16L;
        }
        if (value instanceof LinkedList<?> list) {
            long total = 32;
            for (Object item : list) {
                total += ((byte[]) item).length + 24L;
            }
            return total;
        }
        if (value instanceof LinkedHashMap<?, ?> map) {
            long total = 48;
            for (Map.Entry<?, ?> field : map.entrySet()) {
                total += ((String) field.getKey()).length() + ((byte[]) field.getValue()).length + 48L;
            }
            return total;
        }
        if (value instanceof LinkedHashSet<?> set) {
            long total = 48;
            for (Object member : set) {
                total += ((String) member).length() + 24L;
            }
            return total;
        }
        if (value instanceof Stream stream) {
            long total = 48;
            for (List<byte[]> fields : stream.entries().values()) {
                total += 32;
                for (byte[] field : fields) {
                    total += field.length + 16L;
                }
            }
            return total;
        }
        return 16;
    }
}
