package dev.hamid.redisj.command;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Strict number parsing with the same rules as Redis's {@code string2ll} and {@code strtod} checks.
 *
 * <p>Integers reject a leading {@code +}, leading zeros, {@code -0}, and whitespace. Floats reject
 * {@code NaN}, hexadecimal and trailing type suffixes.
 */
public final class Numbers {

    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private Numbers() {
    }

    /**
     * Parses a strict signed 64-bit integer.
     *
     * @param bytes the text
     * @return the value
     * @throws CommandException if the text is not a valid integer
     */
    public static long parseLong(byte[] bytes) {
        OptionalLong value = tryParseLong(bytes);
        if (value.isEmpty()) {
            throw CommandException.notInteger();
        }
        return value.getAsLong();
    }

    /**
     * Parses a strict signed 64-bit integer without throwing.
     *
     * @param bytes the text
     * @return the value, or an empty optional if the text is not a valid integer
     */
    public static OptionalLong tryParseLong(byte[] bytes) {
        int n = bytes.length;
        if (n == 0 || n > 20) {
            return OptionalLong.empty();
        }
        boolean negative = bytes[0] == '-';
        int i = negative ? 1 : 0;
        if (i >= n) {
            return OptionalLong.empty();
        }
        if (bytes[i] == '0' && (n - i > 1 || negative)) {
            return OptionalLong.empty();
        }
        for (int j = i; j < n; j++) {
            if (bytes[j] < '0' || bytes[j] > '9') {
                return OptionalLong.empty();
            }
        }
        try {
            return OptionalLong.of(Long.parseLong(new String(bytes, StandardCharsets.US_ASCII)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Parses a finite or infinite decimal floating point number.
     *
     * @param bytes the text
     * @return the value; may be infinite if the text overflows
     * @throws CommandException if the text is not a valid float
     */
    public static double parseDouble(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!FLOAT.matcher(text).matches()) {
            throw CommandException.notFloat();
        }
        return Double.parseDouble(text);
    }
}
