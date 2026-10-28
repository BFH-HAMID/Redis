package dev.hamid.redisj.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Incremental, non-blocking RESP2/RESP3 parser.
 *
 * <p>The parser reads from a {@link ByteBuffer} that is in read mode (position to limit is
 * the unread data). Each call to {@link #parse(ByteBuffer)} returns the next complete top-level
 * value, or {@code null} when more bytes are needed. Partial frames are kept safely:
 * an incomplete element leaves the buffer position untouched, and enclosing aggregates
 * stay on an internal stack until their remaining elements arrive. Callers therefore append
 * new bytes, compact the buffer and call {@code parse} again.
 *
 * <p>Requests are normally RESP arrays of bulk strings. A top-level line that does not start
 * with a RESP type marker is parsed as an inline command, split into bulk strings by
 * {@link InlineTokenizer}. Empty inline lines are ignored.
 *
 * <p>Malformed or over-limit input raises {@link RespProtocolException}. After that the
 * parser must be discarded or {@link #reset() reset}, and the connection should be closed.
 *
 * <p>This class is not thread-safe. Each connection owns its own parser.
 */
public final class RespParser {

    /** Hard upper bound for bulk string payloads (512 MB), as in Redis. */
    public static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;

    private static final String MARKERS = "+-:$*_#,%~>(=|";
    private static final Pattern DOUBLE_PATTERN =
            Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private final Limits limits;
    private final Deque<Frame> stack = new ArrayDeque<>();

    /**
     * Resource limits enforced while parsing.
     *
     * @param maxBulkLength     largest accepted bulk payload in bytes, at most {@link #MAX_BULK_LENGTH}
     * @param maxAggregateLength largest accepted element count of an array, set or map
     * @param maxLineLength     longest accepted header or inline line in bytes
     * @param maxDepth          deepest accepted aggregate nesting
     */
    public record Limits(int maxBulkLength, int maxAggregateLength, int maxLineLength, int maxDepth) {

        /** Redis-compatible defaults: 512 MB bulks, 1M elements, 64 KB lines, depth 128. */
        public static final Limits DEFAULT = new Limits(MAX_BULK_LENGTH, 1024 * 1024, 64 * 1024, 128);

        /** Validates the limits. */
        public Limits {
            if (maxBulkLength < 0 || maxBulkLength > MAX_BULK_LENGTH) {
                throw new IllegalArgumentException("maxBulkLength must be between 0 and " + MAX_BULK_LENGTH);
            }
            if (maxAggregateLength < 1 || maxLineLength < 1 || maxDepth < 1) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    /** Creates a parser with default limits. */
    public RespParser() {
        this(Limits.DEFAULT);
    }

    /**
     * Creates a parser with the given limits.
     *
     * @param limits resource limits
     */
    public RespParser(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Parses the next complete top-level value from the buffer.
     *
     * @param buffer a buffer in read mode; its position advances past consumed bytes
     * @return the next value, or {@code null} if more input is needed
     * @throws RespProtocolException if the input is malformed or exceeds a limit
     */
    public RespValue parse(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        while (buffer.hasRemaining()) {
            Step step = readStep(buffer);
            if (step == null) {
                return null;
            }
            if (step.isSkip()) {
                continue;
            }
            if (step.kind() != null) {
                if (stack.size() >= limits.maxDepth()) {
                    throw protocol("nesting too deep");
                }
                Frame frame = new Frame(step.kind(), step.count());
                if (frame.isFull()) {
                    RespValue done = complete(frame.build());
                    if (done != null) {
                        return done;
                    }
                } else {
                    stack.push(frame);
                }
                continue;
            }
            RespValue done = complete(step.value());
            if (done != null) {
                return done;
            }
        }
        return null;
    }

    /**
     * @return {@code true} if an aggregate is partially received
     */
    public boolean inFrame() {
        return !stack.isEmpty();
    }

    /**
     * Discards any partially parsed aggregate. Use after a protocol error or to reuse the parser.
     */
    public void reset() {
        stack.clear();
    }

    /**
     * Attaches a finished value to the innermost open aggregate, closing aggregates as they fill.
     *
     * @return the finished top-level value, or {@code null} if an aggregate is still open
     */
    private RespValue complete(RespValue value) {
        RespValue current = value;
        while (!stack.isEmpty()) {
            Frame top = stack.peek();
            top.items.add(current);
            if (!top.isFull()) {
                return null;
            }
            stack.pop();
            current = top.build();
        }
        return current;
    }

    /**
     * Reads one element starting at the buffer position without consuming it unless complete.
     *
     * @return the step, or {@code null} if the element is incomplete
     */
    private Step readStep(ByteBuffer buf) {
        int start = buf.position();
        char type = (char) (buf.get(start) & 0xFF);
        if (MARKERS.indexOf(type) < 0) {
            if (!stack.isEmpty()) {
                throw protocol("expected a RESP type marker inside an aggregate");
            }
            return readInline(buf, start);
        }
        int lineEnd = findLineEnd(buf, start + 1);
        if (lineEnd < 0) {
            return null;
        }
        byte[] content = slice(buf, start + 1, lineEnd);
        int next = lineEnd + 2;
        switch (type) {
            case '+':
                return consume(buf, next, Step.of(new RespValue.SimpleString(utf8(content))));
            case '-':
                return consume(buf, next, Step.of(new RespValue.Error(utf8(content))));
            case ':':
                return consume(buf, next, Step.of(new RespValue.Integer(parseLong(content, "invalid integer"))));
            case '_':
                if (content.length != 0) {
                    throw protocol("invalid null");
                }
                return consume(buf, next, Step.of(RespValue.Null.INSTANCE));
            case '#':
                return consume(buf, next, Step.of(new RespValue.Boolean(parseBoolean(content))));
            case ',':
                return consume(buf, next, Step.of(new RespValue.Double(parseDouble(utf8(content)))));
            case '*':
            case '>':
                return consume(buf, next, aggregateStep(FrameKind.ARRAY, content, "invalid multibulk length"));
            case '%':
                return consume(buf, next, aggregateStep(FrameKind.MAP, content, "invalid map length"));
            case '~':
                return consume(buf, next, aggregateStep(FrameKind.SET, content, "invalid set length"));
            case '$':
                return readBulk(buf, next, content);
            default:
                throw protocol("unsupported RESP type '" + type + "'");
        }
    }

    private Step readBulk(ByteBuffer buf, int next, byte[] header) {
        long len = parseLong(header, "invalid bulk length");
        if (len == -1) {
            return consume(buf, next, Step.of(RespValue.Null.INSTANCE));
        }
        if (len < 0 || len > limits.maxBulkLength()) {
            throw protocol("invalid bulk length");
        }
        int size = (int) len;
        int payloadEnd = next + size;
        if (payloadEnd + 2 > buf.limit()) {
            return null;
        }
        if (buf.get(payloadEnd) != '\r' || buf.get(payloadEnd + 1) != '\n') {
            throw protocol("bad bulk string terminator");
        }
        byte[] payload = new byte[size];
        buf.get(next, payload);
        return consume(buf, payloadEnd + 2, Step.of(new RespValue.BulkString(payload)));
    }

    private Step aggregateStep(FrameKind kind, byte[] header, String message) {
        long count = parseLong(header, message);
        if (count == -1 && kind == FrameKind.ARRAY) {
            return Step.of(RespValue.Null.INSTANCE);
        }
        if (count < 0 || count > limits.maxAggregateLength()) {
            throw protocol(message);
        }
        return Step.header(kind, (int) count);
    }

    private Step readInline(ByteBuffer buf, int start) {
        int limit = buf.limit();
        int newline = -1;
        for (int i = start; i < limit; i++) {
            if (buf.get(i) == '\n') {
                newline = i;
                break;
            }
        }
        if (newline < 0) {
            if (limit - start > limits.maxLineLength()) {
                throw protocol("too big inline request");
            }
            return null;
        }
        if (newline - start > limits.maxLineLength()) {
            throw protocol("too big inline request");
        }
        int end = newline;
        if (end > start && buf.get(end - 1) == '\r') {
            end--;
        }
        byte[] line = slice(buf, start, end);
        buf.position(newline + 1);
        List<byte[]> tokens = InlineTokenizer.split(line);
        if (tokens.isEmpty()) {
            return Step.skip();
        }
        List<RespValue> items = new ArrayList<>(tokens.size());
        for (byte[] token : tokens) {
            items.add(new RespValue.BulkString(token));
        }
        return Step.of(new RespValue.Array(items));
    }

    /**
     * Finds the CR of the CRLF that ends a header line.
     *
     * @return the index of the CR, or {@code -1} if the line is incomplete
     */
    private int findLineEnd(ByteBuffer buf, int from) {
        int limit = buf.limit();
        for (int i = from; i < limit; i++) {
            byte b = buf.get(i);
            if (b == '\r') {
                if (i + 1 >= limit) {
                    return -1;
                }
                if (buf.get(i + 1) != '\n') {
                    throw protocol("invalid line terminator");
                }
                return i;
            }
            if (b == '\n') {
                throw protocol("invalid line terminator");
            }
            if (i - from > limits.maxLineLength()) {
                throw protocol("too big line");
            }
        }
        return -1;
    }

    private static Step consume(ByteBuffer buf, int newPosition, Step step) {
        buf.position(newPosition);
        return step;
    }

    private static byte[] slice(ByteBuffer buf, int from, int to) {
        byte[] out = new byte[to - from];
        buf.get(from, out);
        return out;
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long parseLong(byte[] content, String message) {
        if (content.length == 0 || content.length > 20) {
            throw protocol(message);
        }
        int first = content[0] == '-' ? 1 : 0;
        if (first == content.length) {
            throw protocol(message);
        }
        for (int j = first; j < content.length; j++) {
            if (content[j] < '0' || content[j] > '9') {
                throw protocol(message);
            }
        }
        try {
            return Long.parseLong(new String(content, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            throw protocol(message);
        }
    }

    private static boolean parseBoolean(byte[] content) {
        if (content.length == 1 && content[0] == 't') {
            return true;
        }
        if (content.length == 1 && content[0] == 'f') {
            return false;
        }
        throw protocol("invalid boolean");
    }

    private static double parseDouble(String text) {
        if (text.equals("inf") || text.equals("+inf")) {
            return Double.POSITIVE_INFINITY;
        }
        if (text.equals("-inf")) {
            return Double.NEGATIVE_INFINITY;
        }
        if (text.equals("nan")) {
            return Double.NaN;
        }
        if (!DOUBLE_PATTERN.matcher(text).matches()) {
            throw protocol("invalid double");
        }
        return Double.parseDouble(text);
    }

    private static RespProtocolException protocol(String message) {
        return new RespProtocolException("Protocol error: " + message);
    }

    /** Kinds of aggregate that can be open on the stack. */
    private enum FrameKind {
        ARRAY, MAP, SET
    }

    /** One parsed element: a complete value, the header of an aggregate, or nothing (empty inline line). */
    private record Step(RespValue value, FrameKind kind, int count) {

        static Step of(RespValue value) {
            return new Step(value, null, 0);
        }

        static Step header(FrameKind kind, int count) {
            return new Step(null, kind, count);
        }

        static Step skip() {
            return new Step(null, null, 0);
        }

        boolean isSkip() {
            return value == null && kind == null;
        }
    }

    /** An aggregate being filled. For maps, {@code expected} counts keys and values. */
    private static final class Frame {

        private final FrameKind kind;
        private final int expected;
        private final List<RespValue> items;

        Frame(FrameKind kind, int count) {
            this.kind = kind;
            this.expected = kind == FrameKind.MAP ? count * 2 : count;
            this.items = new ArrayList<>(Math.min(this.expected, 1024));
        }

        boolean isFull() {
            return items.size() == expected;
        }

        RespValue build() {
            return switch (kind) {
                case ARRAY -> new RespValue.Array(items);
                case SET -> new RespValue.Set(new LinkedHashSet<>(items));
                case MAP -> {
                    Map<RespValue, RespValue> entries = new LinkedHashMap<>();
                    for (int i = 0; i < items.size(); i += 2) {
                        entries.put(items.get(i), items.get(i + 1));
                    }
                    yield new RespValue.Map(entries);
                }
            };
        }
    }
}
