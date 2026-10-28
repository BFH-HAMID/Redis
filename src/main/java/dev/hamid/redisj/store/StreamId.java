package dev.hamid.redisj.store;

/**
 * A stream entry ID: a millisecond timestamp and a sequence number, ordered by time then sequence.
 *
 * @param ms  milliseconds since the epoch
 * @param seq sequence number within the millisecond
 */
public record StreamId(long ms, long seq) implements Comparable<StreamId> {

    /** The smallest possible ID, {@code 0-0}. */
    public static final StreamId MIN = new StreamId(0L, 0L);

    /** The largest possible ID. */
    public static final StreamId MAX = new StreamId(Long.MAX_VALUE, Long.MAX_VALUE);

    @Override
    public int compareTo(StreamId other) {
        int byMs = Long.compare(ms, other.ms);
        return byMs != 0 ? byMs : Long.compare(seq, other.seq);
    }

    @Override
    public String toString() {
        return ms + "-" + seq;
    }
}
