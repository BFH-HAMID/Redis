package dev.hamid.redisj.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The set of keys that have a time to live, with O(1) add, remove and uniform random sampling.
 *
 * <p>Sampling is what the active expiry cycle needs. Elements live in an array, and a position map
 * gives O(1) removal by swapping the last element into the hole.
 */
final class ExpirySet {

    private final List<String> keys = new ArrayList<>();
    private final Map<String, Integer> positions = new HashMap<>();

    void add(String key) {
        if (!positions.containsKey(key)) {
            positions.put(key, keys.size());
            keys.add(key);
        }
    }

    void remove(String key) {
        Integer index = positions.remove(key);
        if (index == null) {
            return;
        }
        int last = keys.size() - 1;
        if (index != last) {
            String moved = keys.get(last);
            keys.set(index, moved);
            positions.put(moved, index);
        }
        keys.remove(last);
    }

    int size() {
        return keys.size();
    }

    /**
     * @param random the random source
     * @return a uniformly chosen key, or {@code null} if the set is empty
     */
    String randomKey(Random random) {
        if (keys.isEmpty()) {
            return null;
        }
        return keys.get(random.nextInt(keys.size()));
    }

    void clear() {
        keys.clear();
        positions.clear();
    }
}
