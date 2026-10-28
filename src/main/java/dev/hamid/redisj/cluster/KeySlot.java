package dev.hamid.redisj.cluster;

import java.nio.charset.StandardCharsets;

/**
 * Maps keys to the 16384 hash slots used by Redis Cluster: CRC16 (XMODEM) of the key, modulo 16384.
 *
 * <p>If a key contains a non-empty {@code {...}} section, only that section is hashed, so related
 * keys can be forced into the same slot.
 */
public final class KeySlot {

    /** Number of hash slots. */
    public static final int SLOTS = 16_384;

    private KeySlot() {
    }

    /**
     * @param key the key name (ISO-8859-1 text, as the server stores keys)
     * @return the slot, between 0 and {@link #SLOTS} - 1
     */
    public static int of(String key) {
        String hashed = key;
        int open = key.indexOf('{');
        if (open >= 0) {
            int close = key.indexOf('}', open + 1);
            if (close > open + 1) {
                hashed = key.substring(open + 1, close);
            }
        }
        return crc16(hashed.getBytes(StandardCharsets.ISO_8859_1)) % SLOTS;
    }

    /**
     * CRC16 with polynomial 0x1021, initial value 0 and no reflection (the XMODEM variant).
     *
     * @param bytes the input
     * @return the 16-bit checksum
     */
    static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
