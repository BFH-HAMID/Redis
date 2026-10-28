package dev.hamid.redisj.config;

import java.util.Locale;

/**
 * Parses Redis-style memory sizes such as {@code 100mb} or {@code 2gb}.
 *
 * <p>Following Redis conventions, {@code k}, {@code m} and {@code g} are decimal
 * (1000-based) while {@code kb}, {@code mb} and {@code gb} are binary (1024-based).
 * Units are case-insensitive and a bare number means bytes.
 */
public final class MemorySize {

    private MemorySize() {
    }

    /**
     * Parses a memory size into a number of bytes.
     *
     * @param text the textual size, for example {@code "256mb"}
     * @return the size in bytes
     * @throws IllegalArgumentException if the text is malformed, uses an unknown unit or overflows
     */
    public static long parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("memory size must not be null");
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == 0) {
            throw new IllegalArgumentException("invalid memory size: " + text);
        }
        long value;
        try {
            value = Long.parseLong(s.substring(0, i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid memory size: " + text, e);
        }
        long multiplier = switch (s.substring(i)) {
            case "", "b" -> 1L;
            case "k" -> 1000L;
            case "kb" -> 1024L;
            case "m" -> 1_000_000L;
            case "mb" -> 1024L * 1024L;
            case "g" -> 1_000_000_000L;
            case "gb" -> 1024L * 1024L * 1024L;
            default -> throw new IllegalArgumentException("unknown memory unit in: " + text);
        };
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("memory size too large: " + text, e);
        }
    }
}
