package dev.hamid.redisj.protocol;

/**
 * The RESP protocol version negotiated for a connection.
 *
 * <p>Connections start as {@link #RESP2}. A successful {@code HELLO 3} switches the
 * connection to {@link #RESP3}.
 */
public enum ProtocolVersion {

    /** RESP2, the default protocol. */
    RESP2(2),

    /** RESP3, enabled by {@code HELLO 3}. */
    RESP3(3);

    private final int number;

    ProtocolVersion(int number) {
        this.number = number;
    }

    /**
     * @return the protocol number as used by {@code HELLO}
     */
    public int number() {
        return number;
    }

    /**
     * Looks up a version by its protocol number.
     *
     * @param number 2 or 3
     * @return the matching version
     * @throws IllegalArgumentException if the number is not a supported version
     */
    public static ProtocolVersion fromNumber(int number) {
        for (ProtocolVersion version : values()) {
            if (version.number == number) {
                return version;
            }
        }
        throw new IllegalArgumentException("unsupported RESP version: " + number);
    }
}
