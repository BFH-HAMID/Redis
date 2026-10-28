package dev.hamid.redisj.store;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A stream value: entries ordered by ID, each holding a flat list of field and value bytes.
 *
 * <p>The last ID is kept separately from the entries, so IDs keep increasing even after the newest
 * entries are deleted or trimmed. Not thread-safe; used only on the execution loop.
 */
public final class Stream {

    private final TreeMap<StreamId, List<byte[]>> entries = new TreeMap<>();
    private StreamId lastId = StreamId.MIN;

    /**
     * @return the largest ID ever added, or {@link StreamId#MIN} for a new stream
     */
    public StreamId lastId() {
        return lastId;
    }

    /**
     * @return the number of entries currently in the stream
     */
    public int size() {
        return entries.size();
    }

    /**
     * @return the entries in ID order, as an unmodifiable view
     */
    public Map<StreamId, List<byte[]>> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /**
     * Appends an entry. The caller must have checked that {@code id} is greater than {@link #lastId()}.
     *
     * @param id     the new entry's ID
     * @param fields alternating field and value bytes
     */
    public void append(StreamId id, List<byte[]> fields) {
        entries.put(id, List.copyOf(fields));
        lastId = id;
    }

    /**
     * @param lo    the lowest ID, inclusive
     * @param hi    the highest ID, inclusive
     * @param count the maximum number of entries to return
     * @param reverse {@code true} to return the highest IDs first
     * @return the matching entries in the requested order
     */
    public List<Map.Entry<StreamId, List<byte[]>>> range(StreamId lo, StreamId hi, long count,
                                                         boolean reverse) {
        if (lo.compareTo(hi) > 0) {
            return List.of();
        }
        NavigableMap<StreamId, List<byte[]>> window = entries.subMap(lo, true, hi, true);
        Collection<Map.Entry<StreamId, List<byte[]>>> view = reverse
                ? window.descendingMap().entrySet() : window.entrySet();
        return take(view, count);
    }

    /**
     * @param exclusive return only entries with IDs strictly greater than this one
     * @param count     the maximum number of entries to return
     * @return the matching entries in ascending order
     */
    public List<Map.Entry<StreamId, List<byte[]>>> after(StreamId exclusive, long count) {
        return take(entries.tailMap(exclusive, false).entrySet(), count);
    }

    /**
     * @param ids the IDs to delete
     * @return the number of entries actually removed
     */
    public int delete(Collection<StreamId> ids) {
        int removed = 0;
        for (StreamId id : ids) {
            if (entries.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes the oldest entries until at most {@code max} remain.
     *
     * @param max the number of entries to keep
     * @return the number of entries removed
     */
    public int trimToLength(long max) {
        int removed = 0;
        while (entries.size() > max) {
            entries.pollFirstEntry();
            removed++;
        }
        return removed;
    }

    private static List<Map.Entry<StreamId, List<byte[]>>> take(
            Collection<Map.Entry<StreamId, List<byte[]>>> view, long count) {
        List<Map.Entry<StreamId, List<byte[]>>> out = new java.util.ArrayList<>();
        for (Map.Entry<StreamId, List<byte[]>> entry : view) {
            if (count >= 0 && out.size() >= count) {
                break;
            }
            out.add(entry);
        }
        return out;
    }
}
