package dev.hamid.redisj.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encodes {@link RespValue}s into RESP wire format.
 *
 * <p>The writer is version-aware. In {@link ProtocolVersion#RESP2} it downgrades RESP3-only
 * kinds: maps become flat arrays of key/value pairs, sets become arrays, booleans become
 * integers, doubles become bulk strings and nulls become {@code $-1}. In
 * {@link ProtocolVersion#RESP3} every kind is encoded natively.
 *
 * <p>Writes are all-or-nothing: {@link #write(RespValue, ByteBuffer)} first computes the exact
 * encoded length and only writes when the buffer has room. Simple strings and errors have CR
 * and LF replaced by spaces, so a value can never break framing.
 *
 * <p>Instances are stateless and thread-safe.
 */
public final class RespWriter {

    private final ProtocolVersion version;

    /** Creates a RESP2 writer. */
    public RespWriter() {
        this(ProtocolVersion.RESP2);
    }

    /**
     * Creates a writer for the given protocol version.
     *
     * @param version the target protocol version
     */
    public RespWriter(ProtocolVersion version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    /**
     * @return the protocol version this writer encodes
     */
    public ProtocolVersion version() {
        return version;
    }

    /**
     * Computes the encoded length of a value.
     *
     * @param value the value
     * @return the number of bytes {@link #write(RespValue, ByteBuffer)} would produce
     */
    public int encodedLength(RespValue value) {
        CountingSink counter = new CountingSink();
        encode(value, counter);
        return counter.count;
    }

    /**
     * Encodes a value into the buffer, if the buffer has enough room.
     *
     * @param value the value to encode
     * @param out   a buffer in write mode
     * @return {@code true} if the value was written; {@code false} if there was not enough room,
     *         in which case the buffer is unchanged
     */
    public boolean write(RespValue value, ByteBuffer out) {
        Objects.requireNonNull(out, "out");
        if (encodedLength(value) > out.remaining()) {
            return false;
        }
        encode(value, new BufferSink(out));
        return true;
    }

    /**
     * Encodes a value into a new byte array.
     *
     * @param value the value to encode
     * @return the encoded bytes
     */
    public byte[] toBytes(RespValue value) {
        ByteBuffer buffer = ByteBuffer.allocate(encodedLength(value));
        encode(value, new BufferSink(buffer));
        return buffer.array();
    }

    private void encode(RespValue value, Sink out) {
        switch (value) {
            case RespValue.SimpleString s -> line(out, '+', s.value());
            case RespValue.Error e -> line(out, '-', sanitize(e.message()));
            case RespValue.Integer i -> line(out, ':', Long.toString(i.value()));
            case RespValue.BulkString b -> bulk(out, b.bytes());
            case RespValue.Array a -> array(out, a.elements());
            case RespValue.Null n -> text(out, version == ProtocolVersion.RESP3 ? "_\r\n" : "$-1\r\n");
            case RespValue.Map m -> map(out, m.entries());
            case RespValue.Set s -> set(out, s.elements());
            case RespValue.Boolean b -> booleanValue(out, b.value());
            case RespValue.Double d -> doubleValue(out, d.value());
        }
    }

    private void array(Sink out, List<RespValue> elements) {
        header(out, '*', elements.size());
        for (RespValue element : elements) {
            encode(element, out);
        }
    }

    private void map(Sink out, Map<RespValue, RespValue> entries) {
        if (version == ProtocolVersion.RESP3) {
            header(out, '%', entries.size());
            for (Map.Entry<RespValue, RespValue> entry : entries.entrySet()) {
                encode(entry.getKey(), out);
                encode(entry.getValue(), out);
            }
        } else {
            header(out, '*', entries.size() * 2L);
            for (Map.Entry<RespValue, RespValue> entry : entries.entrySet()) {
                encode(entry.getKey(), out);
                encode(entry.getValue(), out);
            }
        }
    }

    private void set(Sink out, Set<RespValue> elements) {
        header(out, version == ProtocolVersion.RESP3 ? '~' : '*', elements.size());
        for (RespValue element : elements) {
            encode(element, out);
        }
    }

    private void booleanValue(Sink out, boolean value) {
        if (version == ProtocolVersion.RESP3) {
            line(out, '#', value ? "t" : "f");
        } else {
            line(out, ':', value ? "1" : "0");
        }
    }

    private void doubleValue(Sink out, double value) {
        if (version == ProtocolVersion.RESP3) {
            line(out, ',', formatDouble(value));
        } else {
            bulk(out, formatDouble(value).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void line(Sink out, char type, String content) {
        out.write(type);
        text(out, content);
        crlf(out);
    }

    private static void bulk(Sink out, byte[] payload) {
        header(out, '$', payload.length);
        out.write(payload);
        crlf(out);
    }

    private static void header(Sink out, char type, long count) {
        out.write(type);
        text(out, Long.toString(count));
        crlf(out);
    }

    private static void text(Sink out, String content) {
        out.write(content.getBytes(StandardCharsets.UTF_8));
    }

    private static void crlf(Sink out) {
        out.write('\r');
        out.write('\n');
    }

    private static String sanitize(String message) {
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Formats a double the way Redis does: {@code inf}, {@code -inf}, {@code nan}, and otherwise
     * the shortest decimal form without a trailing {@code .0}.
     *
     * @param value the number
     * @return the textual form
     */
    static String formatDouble(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String s = Double.toString(value);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s.replace('E', 'e');
    }

    /** Byte destination used by the encoder, either counting or writing. */
    private interface Sink {
        void write(int b);

        void write(byte[] bytes);
    }

    /** Counts bytes without storing them. */
    private static final class CountingSink implements Sink {
        private int count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] bytes) {
            count += bytes.length;
        }
    }

    /** Writes bytes into a caller-supplied buffer. */
    private static final class BufferSink implements Sink {
        private final ByteBuffer out;

        BufferSink(ByteBuffer out) {
            this.out = out;
        }

        @Override
        public void write(int b) {
            out.put((byte) b);
        }

        @Override
        public void write(byte[] bytes) {
            out.put(bytes);
        }
    }
}
