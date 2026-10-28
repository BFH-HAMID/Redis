package dev.hamid.redisj.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A value of the RESP wire protocol (RESP2 and RESP3).
 *
 * <p>The nested records are the closed set of value kinds. The RESP2 kinds are
 * {@link SimpleString}, {@link Error}, {@link Integer}, {@link BulkString}, {@link Array}
 * and {@link Null}. The RESP3 kinds are {@link Map}, {@link Set}, {@link Boolean} and
 * {@link Double}.
 *
 * <p>Inside this interface the simple names {@code Map}, {@code Set}, {@code Integer},
 * {@code Boolean}, {@code Double} and {@code Error} refer to the nested records. Use
 * {@code java.util} or {@code java.lang} types fully qualified where needed.
 */
public sealed interface RespValue {

    /**
     * A RESP simple string ({@code +OK\r\n}). Must not contain CR or LF.
     *
     * @param value the text
     */
    record SimpleString(String value) implements RespValue {
        /** Validates the value. */
        public SimpleString {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A RESP error ({@code -ERR message\r\n}).
     *
     * @param message the error text, including the error code prefix
     */
    record Error(String message) implements RespValue {
        /** Validates the message. */
        public Error {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * A signed 64-bit RESP integer ({@code :42\r\n}).
     *
     * @param value the number
     */
    record Integer(long value) implements RespValue {
    }

    /**
     * A binary-safe RESP bulk string ({@code $len\r\n...\r\n}).
     *
     * <p>Equality, hashing and {@code toString} use the byte contents, not the array identity.
     *
     * @param bytes the payload; the array is not copied
     */
    record BulkString(byte[] bytes) implements RespValue {

        /** Validates the payload. */
        public BulkString {
            Objects.requireNonNull(bytes, "bytes");
        }

        /**
         * Creates a bulk string from text encoded as UTF-8.
         *
         * @param text the text
         * @return the bulk string
         */
        public static BulkString of(String text) {
            return new BulkString(text.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * @return the payload length in bytes
         */
        public int length() {
            return bytes.length;
        }

        /**
         * @return the payload decoded as UTF-8
         */
        public String asString() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BulkString other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "BulkString[" + bytes.length + " bytes]";
        }
    }

    /**
     * A RESP array ({@code *n\r\n...}). The element list is copied and made unmodifiable.
     *
     * @param elements the elements in order
     */
    record Array(List<RespValue> elements) implements RespValue {
        /** Copies the elements. */
        public Array {
            elements = List.copyOf(elements);
        }
    }

    /**
     * The RESP null. Encodes as {@code $-1} or {@code *-1} in RESP2 and {@code _} in RESP3.
     */
    record Null() implements RespValue {
        /** The shared null instance. */
        public static final Null INSTANCE = new Null();
    }

    /**
     * A RESP3 map ({@code %n\r\n...}). Insertion order is preserved.
     *
     * @param entries the key/value pairs; copied and made unmodifiable
     */
    record Map(java.util.Map<RespValue, RespValue> entries) implements RespValue {
        /** Copies the entries. */
        public Map {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    /**
     * A RESP3 set ({@code ~n\r\n...}). Insertion order is preserved.
     *
     * @param elements the members; copied and made unmodifiable
     */
    record Set(java.util.Set<RespValue> elements) implements RespValue {
        /** Copies the elements. */
        public Set {
            elements = Collections.unmodifiableSet(new LinkedHashSet<>(elements));
        }
    }

    /**
     * A RESP3 boolean ({@code #t} or {@code #f}).
     *
     * @param value the flag
     */
    record Boolean(boolean value) implements RespValue {
    }

    /**
     * A RESP3 double ({@code ,1.5\r\n}). Supports {@code inf}, {@code -inf} and {@code nan}.
     *
     * @param value the number
     */
    record Double(double value) implements RespValue {
    }
}
